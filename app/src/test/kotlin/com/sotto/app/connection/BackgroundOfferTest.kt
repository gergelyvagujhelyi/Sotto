package com.sotto.app.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundOfferTest {
    private val now = 100L * 24 * 60 * 60_000
    private val hour = 60L * 60_000
    private val day = 24 * hour

    @Test
    fun `nothing is offered once Sotto may run in the background`() {
        assertNull(backgroundOffer(restricted = false, BackgroundOfferState(), now))
        assertNull(backgroundOffer(restricted = false, BackgroundOfferState(lateAt = now - hour, lateMinutes = 30), now))
    }

    @Test
    fun `until Not now it's offered, naming a recent delay`() {
        assertEquals(BackgroundOffer.Plain, backgroundOffer(true, BackgroundOfferState(), now))
        assertEquals(BackgroundOffer.Late(30), backgroundOffer(true, BackgroundOfferState(lateAt = now - hour, lateMinutes = 30), now))
        // A delay from days ago isn't news any more.
        assertEquals(BackgroundOffer.Plain, backgroundOffer(true, BackgroundOfferState(lateAt = now - 2 * day, lateMinutes = 30), now))
    }

    @Test
    fun `after Not now only a delay noticed since brings it back, and not within a week`() {
        // No delay since: gone for good.
        assertNull(backgroundOffer(true, BackgroundOfferState(dismissedAt = now - 30 * day), now))
        // A delay before the Not now doesn't count.
        assertNull(backgroundOffer(true, BackgroundOfferState(dismissedAt = now - 8 * day, lateAt = now - 9 * day, lateMinutes = 30), now))
        // A fresh delay, but Not now was only two days ago.
        assertNull(backgroundOffer(true, BackgroundOfferState(dismissedAt = now - 2 * day, lateAt = now - hour, lateMinutes = 30), now))
        // A fresh delay, a week after Not now.
        assertEquals(
            BackgroundOffer.Late(30),
            backgroundOffer(true, BackgroundOfferState(dismissedAt = now - 8 * day, lateAt = now - hour, lateMinutes = 30), now),
        )
    }
}
