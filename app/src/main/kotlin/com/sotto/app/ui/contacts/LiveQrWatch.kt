package com.sotto.app.ui.contacts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Someone who scans our live QR code adds us, and their name arrives here as a message
 * request a moment later. While a live QR code is on screen, [shown] notes the time; a new
 * request within [WINDOW_MS] of it is very likely from whoever scanned it, so the app opens
 * that chat at once (MainActivity), for the user to accept or not.
 *
 * Only navigation: nothing about the request is trusted for it. A request from someone else
 * that happens to arrive then just opens too, which does no harm.
 */
object LiveQrWatch {
    const val WINDOW_MS = 2 * 60_000L

    @Volatile
    private var shownAt = 0L

    private val _openRequest = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)

    /** (conversation id, contact id) of a request to open now. */
    val openRequest: SharedFlow<Pair<String, String>> = _openRequest.asSharedFlow()

    /** A live QR code of ours is on screen right now. */
    fun shown(nowMs: Long = System.currentTimeMillis()) {
        shownAt = nowMs
    }

    /** A new message request arrived: open it if our QR code was on screen just before. */
    fun newRequest(conversationId: String, contactId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = shownAt
        if (at == 0L || nowMs - at > WINDOW_MS) return false
        return _openRequest.tryEmit(conversationId to contactId)
    }
}
