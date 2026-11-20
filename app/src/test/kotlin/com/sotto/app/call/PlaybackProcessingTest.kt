package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackProcessingTest {

    /** WebRTC's samples as JNI hands them over: native byte order, but a buffer that says big-endian. */
    private fun samples(count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        for (i in 0 until count) floats.put(i, i - count / 2f)
        return buffer.order(ByteOrder.BIG_ENDIAN)
    }

    private fun read(buffer: ByteBuffer, index: Int): Float =
        buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(index)

    @Test
    fun `samples are processed in place, in the phone's byte order`() {
        val buffer = samples(480)
        PlaybackProcessing({ s, n -> for (i in 0 until n) s[i] *= 2f }).process(3, 480, buffer)
        for (i in 0 until 480) assertEquals(2f * (i - 240f), read(buffer, i), 0f)
    }

    @Test
    fun `a buffer shorter than the frames is processed as far as it goes`() {
        val buffer = samples(160)
        var seen = 0
        PlaybackProcessing({ _, n -> seen = n }).process(1, 480, buffer)
        assertEquals(160, seen)
    }

    @Test
    fun `an error stops the processing instead of reaching WebRTC`() {
        var calls = 0
        var reported: Throwable? = null
        val processing = PlaybackProcessing({ _, _ -> calls++; error("boom") }, report = { reported = it })
        val buffer = samples(160)
        processing.process(1, 160, buffer)
        processing.process(1, 160, buffer)
        assertEquals(1, calls)
        assertNotNull(reported)
        assertEquals(0f - 80f, read(buffer, 0), 0f) // the voice plays as it came
    }
}
