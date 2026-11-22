package com.sotto.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.sotto.app.domain.model.MediaPayload
import java.io.File
import java.util.UUID

/** A finished recording: its file in the cache (delete it once read), format and length. */
class VoiceRecording(val file: File, val mime: String, val durationMs: Long)

/**
 * Records one voice message at a time with the phone's own encoder: Opus in Ogg at
 * 16 kbit/s on Android 10 and newer (about 120 KB a minute), AAC at 16 kbit/s before.
 * It stops by itself at [MediaPayload.MAX_VOICE_MS] or when the file would outgrow
 * [MediaPayload.MAX_BYTES], and calls [onLimit] then.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var mime = ""

    val isRecording: Boolean get() = recorder != null

    /** How long the current recording has run, in ms. */
    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0

    /** Loudness of the last stretch, 0..1, for the recording indicator. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** Starts recording; false when the microphone can't be used (busy, or no permission). */
    fun start(onLimit: () -> Unit): Boolean {
        cancel()
        val out = File(context.cacheDir, "voice-${UUID.randomUUID()}")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioSamplingRate(48_000)
                mime = "audio/ogg"
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(16_000)
                mime = "audio/mp4"
            }
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(BITRATE)
            r.setMaxDuration(MediaPayload.MAX_VOICE_MS.toInt())
            r.setMaxFileSize(MediaPayload.MAX_BYTES.toLong() - SIZE_MARGIN)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    onLimit()
                }
            }
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    /** Stops and returns the recording; null when it was too short or failed. */
    fun stop(): VoiceRecording? {
        val r = recorder ?: return null
        val out = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (out == null) return null
        if (!ok || duration < MIN_MS || out.length() == 0L || out.length() > MediaPayload.MAX_BYTES) {
            out.delete()
            return null
        }
        return VoiceRecording(out, mime, duration.coerceAtMost(MediaPayload.MAX_VOICE_MS))
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }

    companion object {
        const val BITRATE = 16_000
        /** Shorter than this is a tap, not a message. */
        const val MIN_MS = 700L
        /** Room for the container's last pages after the size limit trips. */
        private const val SIZE_MARGIN = 4_000
    }
}
