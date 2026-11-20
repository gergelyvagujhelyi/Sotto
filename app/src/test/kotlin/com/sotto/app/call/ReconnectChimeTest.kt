package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ReconnectChimeTest {
    private val chime = ReconnectChime.synthesize()

    @Test
    fun `it lasts just under a second`() {
        assertEquals((ReconnectChime.DURATION_SEC * ReconnectChime.SAMPLE_RATE).toInt(), chime.size)
    }

    @Test
    fun `it is soft and never clips`() {
        val peak = chime.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 21_500..23_500)
    }

    @Test
    fun `it starts and ends without a click`() {
        assertTrue(chime.take(8).all { abs(it.toInt()) < 300 })
        assertTrue(chime.takeLast(8).all { abs(it.toInt()) < 300 })
    }

    @Test
    fun `two notes, the higher one landing louder, then fading away`() {
        fun rms(fromSec: Double, toSec: Double): Double {
            val slice = chime.slice((fromSec * ReconnectChime.SAMPLE_RATE).toInt() until (toSec * ReconnectChime.SAMPLE_RATE).toInt())
            return kotlin.math.sqrt(slice.sumOf { it.toDouble() * it } / slice.size)
        }
        assertTrue(rms(0.09, 0.2) > rms(0.0, 0.08)) // the second note lands louder
        assertTrue(rms(0.3, 0.45) > 1_000) // and rings out
        assertTrue(rms(0.3, 0.45) > rms(0.75, 0.9)) // then fades away
    }

    /** Also leaves a preview to listen to: app/build/reconnect-chime.wav. */
    @Test
    fun `render a preview`() {
        val data = ByteBuffer.allocate(chime.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        chime.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + chime.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(ReconnectChime.SAMPLE_RATE); putInt(ReconnectChime.SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(chime.size * 2)
        }
        File("build").mkdirs()
        File("build/reconnect-chime.wav").writeBytes(header.array() + data.array())
    }
}
