package com.sotto.app.media

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Plays one voice message at a time, straight from memory: the decrypted audio never
 * touches the disk. Starting another stops the one playing, as in WhatsApp.
 */
@Stable
class VoicePlayer {
    private var player: MediaPlayer? = null

    /** The message playing (or paused mid-way), and where it is, in ms. */
    var currentId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /** Plays [audio] for [messageId], or pauses/resumes it when it's the current one. */
    fun toggle(messageId: String, audio: ByteArray) {
        val p = player
        if (p != null && currentId == messageId) {
            if (p.isPlaying) {
                p.pause()
                isPlaying = false
            } else {
                p.start()
                isPlaying = true
            }
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(BytesSource(audio))
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                currentId = null
                it.release()
                if (player === it) player = null
            }
            mp.prepare()
            mp.start()
            player = mp
            currentId = messageId
            isPlaying = true
            positionMs = 0
        } catch (e: Exception) {
            runCatching { mp.release() }
            throw e
        }
    }

    /** Called every frame or so while playing, to move the progress along. */
    fun tick() {
        val p = player ?: return
        runCatching { positionMs = p.currentPosition.toLong() }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        currentId = null
        isPlaying = false
        positionMs = 0
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }
}
