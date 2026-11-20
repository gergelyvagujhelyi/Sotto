package com.sotto.app.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The chime for a call whose lost connection is back: two clean notes a fifth apart, quick
 * and airy like glass, the second landing a little louder and ringing out. Synthesized (sine
 * tones with a touch of overtone and a faint glassy tick on the touch), so it's our own and
 * needs no sound file. Pure, for tests.
 */
internal object ReconnectChime {
    const val SAMPLE_RATE = 48_000
    const val DURATION_SEC = 0.95

    private class Note(val startSec: Double, val freq: Double, val gain: Double, val decaySec: Double)

    private val NOTES = listOf(
        Note(0.000, 783.99, 0.65, 0.16), // G5
        Note(0.085, 1174.66, 1.00, 0.40), // D6, a fifth up: it resolves
    )

    /** (frequency ratio, amplitude, decay in s): the overtone and the glassy tick die away fast. */
    private val PARTIALS = listOf(Triple(1.0, 1.0, 9.0), Triple(2.0, 0.07, 0.12), Triple(2.92, 0.05, 0.035))
    private const val ATTACK_SEC = 0.004
    private const val FADE_OUT_SEC = 0.12
    private const val PEAK = 0.7

    /** 16-bit mono PCM at [SAMPLE_RATE], peaking at 70 % of full scale. */
    fun synthesize(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val n = (DURATION_SEC * sampleRate).toInt()
        val mix = DoubleArray(n)
        for (note in NOTES) {
            val start = (note.startSec * sampleRate).toInt()
            for (i in start until n) {
                val t = (i - start).toDouble() / sampleRate
                // A raised-cosine touch: soft, without a click.
                val attack = if (t < ATTACK_SEC) 0.5 - 0.5 * cos(PI * t / ATTACK_SEC) else 1.0
                val envelope = attack * exp(-t / note.decaySec)
                var sample = 0.0
                for ((ratio, amplitude, decay) in PARTIALS) {
                    sample += amplitude * exp(-t / decay) * sin(2 * PI * note.freq * ratio * t)
                }
                mix[i] += note.gain * envelope * sample
            }
        }
        val fade = (FADE_OUT_SEC * sampleRate).toInt()
        for (k in 0 until fade) mix[n - 1 - k] *= k.toDouble() / fade
        val peak = mix.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
        val scale = PEAK * Short.MAX_VALUE / peak
        return ShortArray(n) { (mix[it] * scale).roundToInt().toShort() }
    }
}

/**
 * Sounds of our own during a call: the chime when a lost connection is back. Played on the
 * call's audio route (earpiece, speaker or headset, at the call volume); the peer doesn't
 * hear it.
 */
class CallSounds(private val scope: CoroutineScope) {
    @Volatile
    private var chime: ShortArray? = null
    private var preparing: Job? = null

    /** Render the chime ahead of time (it takes a moment of CPU), off the main thread. */
    fun prepare() {
        if (chime != null || preparing?.isActive == true) return
        preparing = scope.launch(Dispatchers.Default) { chime = ReconnectChime.synthesize() }
    }

    fun playReconnected() {
        scope.launch(Dispatchers.Default) {
            val samples = chime ?: ReconnectChime.synthesize().also { chime = it }
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(ReconnectChime.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "No reconnected chime: ${e.message}")
                return@launch
            }
            try {
                track.write(samples, 0, samples.size)
                track.setVolume(CHIME_VOLUME)
                track.play()
                delay(samples.size * 1000L / ReconnectChime.SAMPLE_RATE + 200)
            } catch (e: Exception) {
                Log.w(TAG, "Reconnected chime failed: ${e.message}")
            } finally {
                track.release()
            }
        }
    }

    private companion object {
        const val TAG = "CallSounds"
        const val CHIME_VOLUME = 0.7f
    }
}
