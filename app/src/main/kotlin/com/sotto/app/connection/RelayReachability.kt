package com.sotto.app.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Our relay answered an HTTP request: it's reachable. The socket, waiting out its reconnect
 * backoff after an outage, can try now (RelayConnectionPolicy), so "Connecting…" doesn't stay up
 * for half a minute while messages already go through. A plain signal, so the HTTP client needn't
 * know the socket (which depends on it).
 */
@Singleton
class RelayReachability @Inject constructor() {
    private val _answered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val answered: SharedFlow<Unit> = _answered.asSharedFlow()

    fun answered() {
        _answered.tryEmit(Unit)
    }

    private val uploads = java.util.concurrent.atomic.AtomicInteger(0)
    private val _noUploads = kotlinx.coroutines.flow.MutableStateFlow(true)

    /**
     * Runs a message upload, marked as such: on a slow line (a few KB/s) the socket's reconnect
     * attempts (TLS and a signed sign-in, kilobytes each) starved it, and a photo only went once
     * the screen was locked and the socket closed. The socket waits for uploads (awaitNoUploads).
     */
    suspend fun <T> uploading(block: suspend () -> T): T {
        if (uploads.incrementAndGet() == 1) _noUploads.value = false
        try {
            return block()
        } finally {
            if (uploads.decrementAndGet() == 0) _noUploads.value = true
        }
    }

    /** Waits until no message upload is going on, at most [maxMs]. */
    suspend fun awaitNoUploads(maxMs: Long) {
        kotlinx.coroutines.withTimeoutOrNull(maxMs) { _noUploads.first { it } }
    }
}
