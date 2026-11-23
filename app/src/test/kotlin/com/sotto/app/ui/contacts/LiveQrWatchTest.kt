package com.sotto.app.ui.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQrWatchTest {

    @Test
    fun `a request soon after our QR code was on screen is opened`() {
        LiveQrWatch.shown(nowMs = 1_000_000)
        assertTrue(LiveQrWatch.newRequest("c1", "u1", nowMs = 1_000_000 + 20_000))
    }

    @Test
    fun `a request long after, is left in the list`() {
        LiveQrWatch.shown(nowMs = 2_000_000)
        assertFalse(LiveQrWatch.newRequest("c2", "u2", nowMs = 2_000_000 + LiveQrWatch.WINDOW_MS + 1))
    }
}
