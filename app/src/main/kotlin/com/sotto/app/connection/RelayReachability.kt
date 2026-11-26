package com.sotto.app.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
}
