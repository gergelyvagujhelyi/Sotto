package com.sotto.app.connection

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the chat list's card says: [Plain] the offer, [Late] with the delay that was noticed. */
sealed interface BackgroundOffer {
    data object Plain : BackgroundOffer
    data class Late(val minutes: Int) : BackgroundOffer
}

/** What the offer depends on, besides whether Android restricts Sotto: kept in [BackgroundOfferStore]. */
data class BackgroundOfferState(
    /** When the user last said "Not now"; 0 if never. */
    val dismissedAt: Long = 0,
    /** When a message was last noticed to have arrived late; 0 if never. */
    val lateAt: Long = 0,
    val lateMinutes: Int = 0,
)

/**
 * A message that waited on the relay this long before the phone fetched it arrived late: very
 * likely Android (or the phone maker's battery saver) held Sotto back, though a phone that was
 * off or offline looks the same. Measured on the relay's clock (its arrival time against the
 * relay's Date header when fetched), so a wrong phone clock doesn't count. A debug build
 * notices a minute, to test it.
 */
object LateDelivery {
    val THRESHOLD_MS: Long = if (com.sotto.app.BuildConfig.DEBUG) 60_000L else 10L * 60_000
    /** A delay counts for the card this long after it was noticed. */
    const val RECENT_MS = 24L * 60 * 60_000
    /** After "Not now", a delay brings the card back no sooner than this. */
    const val QUIET_MS = 7L * 24 * 60 * 60_000
}

/**
 * Whether the chat list offers to let Sotto run in the background, and how: only while Android
 * restricts it. Until the user says "Not now" it's offered, naming a recent delay if one was
 * noticed. After "Not now" only a delay noticed since brings it back, a week later at the earliest.
 */
fun backgroundOffer(restricted: Boolean, state: BackgroundOfferState, now: Long): BackgroundOffer? {
    if (!restricted) return null
    val recentLate = state.lateAt > 0 && now - state.lateAt <= LateDelivery.RECENT_MS && state.lateAt > state.dismissedAt
    return when {
        state.dismissedAt == 0L -> if (recentLate) BackgroundOffer.Late(state.lateMinutes) else BackgroundOffer.Plain
        recentLate && now - state.dismissedAt >= LateDelivery.QUIET_MS -> BackgroundOffer.Late(state.lateMinutes)
        else -> null
    }
}

/** Keeps [BackgroundOfferState] on the phone (not secret: two times and a number). */
@Singleton
class BackgroundOfferStore @Inject constructor(@ApplicationContext context: Context) {
    // Read off the main thread: this is made at app start (MessageRepositoryImpl), and the card
    // needs it only once the chat list shows.
    private val prefs by lazy { context.getSharedPreferences("background_offer", Context.MODE_PRIVATE) }
    private val _state = MutableStateFlow(BackgroundOfferState())
    val state: StateFlow<BackgroundOfferState> = _state.asStateFlow()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { _state.value = read() }
    }

    private fun read() = BackgroundOfferState(
        dismissedAt = prefs.getLong(DISMISSED_AT, 0),
        lateAt = prefs.getLong(LATE_AT, 0),
        lateMinutes = prefs.getInt(LATE_MINUTES, 0),
    )

    /** "Not now". */
    @Synchronized
    fun dismiss(now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(DISMISSED_AT, now) }
        _state.value = read()
    }

    /** A fetched message had waited [delayMs] on the relay (only called past the threshold). */
    @Synchronized
    fun noticedLate(delayMs: Long, now: Long = System.currentTimeMillis()) {
        prefs.edit {
            putLong(LATE_AT, now)
            putInt(LATE_MINUTES, (delayMs / 60_000).toInt().coerceAtLeast(1))
        }
        _state.value = read()
    }

    private companion object {
        const val DISMISSED_AT = "dismissed_at"
        const val LATE_AT = "late_at"
        const val LATE_MINUTES = "late_minutes"
    }
}
