package com.sotto.app.data.media

import android.content.Context
import com.sotto.app.call.WebRtcEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * One file, straight from phone to phone over a WebRTC data channel (ADR-29). It's given
 * STUN servers only, never TURN, so it can't fall back to the relay: either the phones
 * reach each other, or it fails and the file goes through the store. ICE isn't trickled:
 * each side sends its description once, with every candidate in it, so the handshake is
 * two messages. The bytes sent are the file's encrypted form, so the receiver checks them
 * exactly as a download from the store.
 */
class DirectLink(context: Context, stunUrls: List<String>) {
    private val pc: PeerConnection
    private val gathered = CompletableDeferred<Unit>()
    private val opened = CompletableDeferred<Unit>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var channel: DataChannel? = null
    private val candidates = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        val factory = sharedFactory(context)
        val config = PeerConnection.RTCConfiguration(stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Keeps gathering as networks appear: a factory that just started may not have
            // found the phone's interfaces yet when the first round ends.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                log("gathering $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onDataChannel(dc: DataChannel?) {
                dc?.let { attach(it) }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                log("ice $state")
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    opened.completeExceptionally(IllegalStateException("No direct path"))
                    incoming.close(IllegalStateException("Direct connection lost"))
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.sdp?.let { candidates.add(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: error("No peer connection")
    }

    private fun attach(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                log("channel ${dc.state()}")
                when (dc.state()) {
                    DataChannel.State.OPEN -> opened.complete(Unit)
                    DataChannel.State.CLOSED -> {
                        opened.completeExceptionally(IllegalStateException("Channel closed"))
                        incoming.close()
                    }
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                incoming.trySend(bytes)
            }
        })
        if (dc.state() == DataChannel.State.OPEN) opened.complete(Unit)
    }

    /** The receiver's side: opens the channel and returns its offer, all candidates included. */
    suspend fun offer(): String {
        attach(pc.createDataChannel("file", DataChannel.Init().apply { ordered = true }))
        val sdp = sdp { pc.createOffer(it, MediaConstraints()) }
        set { pc.setLocalDescription(it, sdp) }
        return localDescription()
    }

    /** The sender's side: takes the receiver's offer and returns the answer. */
    suspend fun answer(offer: String): String {
        set { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, offer)) }
        val sdp = sdp { pc.createAnswer(it, MediaConstraints()) }
        set { pc.setLocalDescription(it, sdp) }
        return localDescription()
    }

    suspend fun acceptAnswer(answer: String) =
        set { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }

    suspend fun awaitOpen(timeoutMs: Long) = withTimeout(timeoutMs) { opened.await() }

    /** Sends [file] in 64 KiB messages, keeping at most 1 MiB queued. */
    suspend fun send(file: File, onProgress: (Long) -> Unit) {
        val dc = channel ?: error("No channel")
        val buffer = ByteArray(CHUNK)
        var sent = 0L
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                while (dc.bufferedAmount() > MAX_QUEUED) {
                    if (dc.state() != DataChannel.State.OPEN) error("Channel closed")
                    delay(10)
                }
                if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(buffer.copyOf(n)), true))) error("Send failed")
                sent += n
                onProgress(sent)
            }
        }
        // Everything handed over before the caller says it's done.
        withTimeout(60_000) { while (dc.bufferedAmount() > 0) delay(20) }
    }

    /** Writes exactly [total] bytes that arrive into [out]. */
    suspend fun receive(out: OutputStream, total: Long, onProgress: (Long) -> Unit) {
        var got = 0L
        while (got < total) {
            val chunk = withTimeout(CHUNK_TIMEOUT_MS) { incoming.receive() }
            if (got + chunk.size > total) error("More than expected")
            out.write(chunk)
            got += chunk.size
            onProgress(got)
        }
    }

    fun close() {
        runCatching { channel?.close() }
        runCatching { pc.dispose() }
    }

    private suspend fun localDescription(): String {
        // Without trickle: wait for the candidates (a few seconds at most), then a moment
        // for the rest of them.
        runCatching {
            withTimeout(GATHER_TIMEOUT_MS) {
                while (candidates.isEmpty()) delay(100)
                withTimeout(SETTLE_MS) { gathered.await() }
            }
        }
        var sdp = pc.localDescription?.description ?: error("No local description")
        // Sometimes the description comes without the candidates found meanwhile: add them,
        // or the other phone can only find this one by luck.
        if (sdp.lines().none { it.startsWith("a=candidate") } && candidates.isNotEmpty()) {
            sdp = sdp.trimEnd() + "\r\n" + candidates.joinToString("") { "a=${it.removePrefix("a=")}\r\n" } + "a=end-of-candidates\r\n"
        }
        log("description with ${sdp.lines().count { it.startsWith("a=candidate") }} candidates: " +
            sdp.lines().filter { it.startsWith("a=candidate") }.joinToString(" | ") { it.split(" ").let { f -> "${f.getOrNull(7)} ${f.getOrNull(2)}" } })
        return sdp
    }

    private fun log(text: String) {
        if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.d("DirectLink", text)
    }

    private suspend fun sdp(create: (SdpObserver) -> Unit): SessionDescription = suspendCoroutine { cont ->
        create(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        })
    }

    private suspend fun set(apply: (SdpObserver) -> Unit): Unit = suspendCoroutine { cont ->
        apply(object : SdpObserver {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        })
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_QUEUED = 1024L * 1024
        const val GATHER_TIMEOUT_MS = 8_000L
        const val SETTLE_MS = 1_500L

        @Volatile private var factory: PeerConnectionFactory? = null

        /** One factory for the process: it watches the networks, so it must have had time to find them. */
        fun sharedFactory(context: Context): PeerConnectionFactory =
            factory ?: synchronized(this) {
                factory ?: run {
                    WebRtcEngine.initOnce(context)
                    PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
                }
            }
        const val CHUNK_TIMEOUT_MS = 20_000L
    }
}
