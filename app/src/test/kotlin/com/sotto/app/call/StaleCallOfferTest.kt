package com.sotto.app.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleCallOfferTest {
    private val now = 1_800_000_000_000L
    private val window = 5 * 60 * 1000L

    @Test
    fun `fresh offer is accepted`() {
        assertFalse(isStaleCallOffer("3f2b5c1e-1111-4222-8333-944455556666.${now - 60_000}", now, window))
        assertFalse(isStaleCallOffer("3f2b5c1e-1111-4222-8333-944455556666.${now + 60_000}", now, window))
    }

    @Test
    fun `replayed old offer and far-future offer are stale`() {
        assertTrue(isStaleCallOffer("3f2b5c1e-1111-4222-8333-944455556666.${now - 10 * 60_000}", now, window))
        assertTrue(isStaleCallOffer("3f2b5c1e-1111-4222-8333-944455556666.${now + 10 * 60_000}", now, window))
    }

    @Test
    fun `call ids from older clients (plain uuid) are accepted`() {
        assertFalse(isStaleCallOffer("3f2b5c1e-1111-4222-8333-944455556666", now, window))
        assertFalse(isStaleCallOffer("garbage.not-a-number", now, window))
    }
}
