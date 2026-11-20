package com.sotto.app.call

import android.util.Log
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Processing of the incoming voice in every call, inside WebRTC's own playback (ADR-23): the
 * [leveler] lifts quiet voices, and more with Louder.
 *
 * The WebRTC library keeps one audio processing module for the whole app, and its destroy()
 * leaves that one dangling. So the factory is made once, when the first call needs it, and
 * never destroyed; each call's PeerConnectionFactory holds its own reference to the module.
 */
internal object CallAudioProcessing {
    val leveler = VoiceLeveler()

    /** Null if it couldn't be made: calls then use WebRTC's default processing, unlevelled. */
    val factory: ExternalAudioProcessingFactory? by lazy {
        try {
            ExternalAudioProcessingFactory().apply {
                setRenderPreProcessing(PlaybackProcessing(leveler::process))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Voice levelling unavailable", t)
            null
        }
    }

    const val TAG = "VoiceLeveler"
}

/**
 * Hands WebRTC's playback samples, 10 ms at a time, to [processSamples], and never throws into
 * WebRTC's audio thread: after an error the voice plays unprocessed.
 */
internal class PlaybackProcessing(
    private val processSamples: (FloatArray, Int) -> Unit,
    private val report: (Throwable) -> Unit = { Log.e(CallAudioProcessing.TAG, "Voice levelling stopped", it) },
) : ExternalAudioProcessingFactory.AudioProcessing {
    private var samples = FloatArray(MAX_FRAMES)
    @Volatile private var failed = false

    override fun initialize(sampleRateHz: Int, numChannels: Int) {}

    override fun reset(newRate: Int) {}

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (failed) return
        try {
            processBuffer(buffer, numFrames)
        } catch (t: Throwable) {
            failed = true
            report(t)
        }
    }

    /**
     * [buffer] is WebRTC's own memory for the samples, [frames] floats in the phone's byte
     * order (a direct buffer from JNI says big-endian until told otherwise). Changed in place.
     */
    fun processBuffer(buffer: ByteBuffer, frames: Int) {
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = minOf(frames, floats.remaining())
        if (n <= 0) return
        if (samples.size < n) samples = FloatArray(n)
        floats.get(samples, 0, n)
        processSamples(samples, n)
        floats.rewind()
        floats.put(samples, 0, n)
    }

    private companion object {
        /** 10 ms at 48 kHz, the most WebRTC's processing uses; larger blocks grow the array. */
        const val MAX_FRAMES = 480
    }
}
