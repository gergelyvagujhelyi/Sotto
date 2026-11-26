package com.sotto.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun `a session that lasted starts the backoff over, one that dropped soon doesn't`() {
        assertTrue(backoffStartsOver(signedInAt = 1_000, now = 31_000, callActive = false))
        // Dropped after 5 s (a shaky Wi-Fi): the next wait grows, no reconnect every few seconds.
        assertFalse(backoffStartsOver(signedInAt = 1_000, now = 6_000, callActive = false))
        // Never signed in: the attempt failed.
        assertFalse(backoffStartsOver(signedInAt = 0, now = 60_000, callActive = false))
    }

    @Test
    fun `during a call every session that signed in starts it over, as before`() {
        assertTrue(backoffStartsOver(signedInAt = 1_000, now = 2_000, callActive = true))
        assertFalse(backoffStartsOver(signedInAt = 0, now = 2_000, callActive = true))
    }
}
