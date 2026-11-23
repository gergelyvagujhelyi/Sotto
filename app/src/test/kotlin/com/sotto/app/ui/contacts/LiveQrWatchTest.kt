package com.sotto.app.ui.contacts

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQrWatchTest {

    @After
    fun tearDown() = LiveQrWatch.hidden(nowMs = 0)

    @Test
    fun `a request while our code is on screen opens the scanner`() {
        LiveQrWatch.shown()
        assertTrue(LiveQrWatch.newRequest("c1", "u1", nowMs = 1_000_000))
    }

    @Test
    fun `a request just after the code was closed still does`() {
        LiveQrWatch.shown()
        LiveQrWatch.hidden(nowMs = 1_000_000)
        assertTrue(LiveQrWatch.newRequest("c2", "u2", nowMs = 1_000_000 + LiveQrWatch.GRACE_MS))
    }

    @Test
    fun `a stranger's request later doesn't pull us into the scanner`() {
        LiveQrWatch.shown()
        LiveQrWatch.hidden(nowMs = 2_000_000)
        assertFalse(LiveQrWatch.newRequest("c3", "u3", nowMs = 2_000_000 + LiveQrWatch.GRACE_MS + 1))
    }
}
