package com.sotto.app.notification

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri

/** The sounds Settings > Sounds lets the user choose. */
enum class SoundKind { MESSAGES, GROUPS, CALLS }

/**
 * A notification channel keeps the sound it was made with: after that only the user can change
 * it, in Android's settings. So a sound chosen in Sotto goes on a new channel, its id the base
 * with the next generation, and the old one is deleted. Ids are never used
 * twice: Android brings a deleted channel back, old settings and all, when an app makes one
 * with its id again.
 */
internal data class SoundChannelIds(val prefix: String, val firstGeneration: Int) {

    /** The channel in use: the newest of [existing], or the first generation when there's none yet. */
    fun current(existing: Collection<String>): String =
        id(existing.mapNotNull(::generation).maxOrNull() ?: firstGeneration)

    /** The channel that replaces [current]. */
    fun next(current: String): String = id((generation(current) ?: firstGeneration) + 1)

    /** Every one of [existing] but [keep]: older generations, left over if a replacement stopped halfway. */
    fun stale(existing: Collection<String>, keep: String): List<String> =
        existing.filter { it != keep && generation(it) != null }

    fun generation(id: String): Int? =
        if (id.startsWith(prefix)) id.removePrefix(prefix).toIntOrNull()?.takeIf { it >= firstGeneration } else null

    private fun id(generation: Int) = "$prefix$generation"

    companion object {
        /** 1:1 chats, missed calls aside. `messages_v2` was the only one up to 0.7.26. */
        val MESSAGES = SoundChannelIds("messages_v", 2)
        /** Group chats; before 0.7.27 they used the messages channel. */
        val GROUPS = SoundChannelIds("groups_v", 1)
    }
}

/**
 * The call ringtone setting, as stored: null follows the phone's ringtone, [SILENT] rings
 * without sound (it still vibrates), anything else is the chosen sound's URI.
 */
object CallRingtone {
    const val SILENT = ""

    /** The setting for what the sound picker returned. */
    fun fromPicked(picked: Uri?): String? = when {
        picked == null -> SILENT
        picked == Settings.System.DEFAULT_RINGTONE_URI -> null
        else -> picked.toString()
    }

    /** The sound to ring with; null for none. */
    fun uri(setting: String?): Uri? = when (setting) {
        null -> Settings.System.DEFAULT_RINGTONE_URI
        SILENT -> null
        else -> setting.toUri()
    }
}

/** What Android's sound picker should open with. */
data class SoundPickerRequest(
    val kind: SoundKind,
    /** The sound chosen now; null for none. */
    val current: Uri?,
    val title: String,
)

/** The picker's answer: [uri] is the chosen sound, null for none. */
data class PickedSound(val uri: Uri?)

/**
 * Android's own sound list (RingtoneManager's picker), with Default and None at the top. Null
 * when the user backed out. The caller keeps which sound it asked for, so it survives the app
 * being recreated while the picker is open.
 */
class PickSound : ActivityResultContract<SoundPickerRequest, PickedSound?>() {

    override fun createIntent(context: Context, input: SoundPickerRequest): Intent {
        val calls = input.kind == SoundKind.CALLS
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                if (calls) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION,
            )
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                if (calls) Settings.System.DEFAULT_RINGTONE_URI else Settings.System.DEFAULT_NOTIFICATION_URI,
            )
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, input.current)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, input.title)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedSound? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        return PickedSound(uri)
    }
}

/** The sound's name as Android's picker shows it; null for none, or when it can't be read. */
fun soundTitle(context: Context, uri: Uri?): String? {
    uri ?: return null
    return runCatching {
        // A Ringtone prepares a player for its sound at once; stop() releases it.
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return null
        try { ringtone.getTitle(context) } finally { ringtone.stop() }
    }.getOrNull()
}
