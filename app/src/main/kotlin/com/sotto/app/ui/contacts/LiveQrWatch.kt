package com.sotto.app.ui.contacts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Someone who scans our live QR code adds us, and their name arrives here as a message
 * request a moment later. While a live QR code is on screen, [shown] notes the time; a new
 * request within [WINDOW_MS] of it is very likely from whoever scanned it. Their phone now
 * shows its own code ("Your turn"), so the app swaps roles: New chat's QR code screen turns
 * to Scan (MainActivity, QrExchangeScreen), and scanning their live code verifies them and
 * accepts the request in one go.
 *
 * Only navigation: nothing about the request is trusted for it. Backing out of the scanner
 * leaves an ordinary request in the list.
 */
object LiveQrWatch {
    /** New chat's SavedStateHandle key: open Scan to answer their scan (QrExchangeScreen). */
    const val SCAN_BACK_REQUEST_KEY = "scan_back_request"

    /** How long after the code left the screen a request still counts (it's on its way). */
    const val GRACE_MS = 15_000L

    @Volatile
    private var visible = false

    @Volatile
    private var hiddenAt = 0L

    private val _openRequest = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)

    /** (conversation id, contact id) of a request to open now. */
    val openRequest: SharedFlow<Pair<String, String>> = _openRequest.asSharedFlow()

    /** A live QR code of ours is on screen right now. */
    fun shown() {
        visible = true
    }

    /**
     * Our code left the screen. A request still counts for [GRACE_MS] (it may be on its way),
     * not longer: after that, a stranger's request mustn't pull the user into the scanner.
     */
    fun hidden(nowMs: Long = System.currentTimeMillis()) {
        if (visible) hiddenAt = nowMs
        visible = false
    }

    /** A new message request arrived: open the scanner if our QR code is (or just was) on screen. */
    fun newRequest(conversationId: String, contactId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val recent = visible || (hiddenAt != 0L && nowMs - hiddenAt <= GRACE_MS)
        if (!recent) return false
        return _openRequest.tryEmit(conversationId to contactId)
    }
}
