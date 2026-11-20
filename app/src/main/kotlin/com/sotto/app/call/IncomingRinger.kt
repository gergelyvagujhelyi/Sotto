package com.sotto.app.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rings and vibrates for an incoming call, like the phone app does, until it is answered,
 * declined or over. The app does this itself rather than through the notification's sound:
 * that depends on how Android treats the notification (heads-up or not, the app paused in
 * the background), and some phones stayed silent.
 */
@Singleton
class IncomingRinger @Inject constructor(@ApplicationContext private val context: Context) {
    private var ringtone: Ringtone? = null
    private var vibrating = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    /** Start ringing (no-op while already ringing). */
    @Synchronized
    fun start() {
        if (ringtone != null || vibrating) return
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val plan = ringPlan(
            ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            interruptionFilter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        Log.i(TAG, "Incoming call: ring=${plan.sound} vibrate=${plan.vibrate}")
        if (plan.sound) {
            try {
                ringtone = RingtoneManager.getRingtone(
                    context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                )?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "No ringtone: ${e.message}")
            }
        }
        if (plan.vibrate) {
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0) // repeat from the start
            try {
                vibrator?.let { v ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(
                            effect,
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                        )
                    }
                    vibrating = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "No vibration: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
    }

    private companion object {
        const val TAG = "IncomingRinger"
        /** Off, on: one second each, like a phone call. */
        val VIBRATION_PATTERN = longArrayOf(0, 1_000, 1_000)
    }
}

internal data class RingPlan(val sound: Boolean, val vibrate: Boolean)

/**
 * What an incoming call may do: ring and vibrate in normal mode, vibrate in vibrate mode,
 * nothing in silent mode or while Do Not Disturb holds back interruptions.
 */
internal fun ringPlan(ringerMode: Int, interruptionFilter: Int): RingPlan = when {
    interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> RingPlan(sound = false, vibrate = false)
    ringerMode == AudioManager.RINGER_MODE_SILENT -> RingPlan(sound = false, vibrate = false)
    ringerMode == AudioManager.RINGER_MODE_VIBRATE -> RingPlan(sound = false, vibrate = true)
    else -> RingPlan(sound = true, vibrate = true)
}
