package com.sotto.app.call

import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OfferVerdictTest {
    private val peer = "peer-user-id"
    private val lowId = "1a000000-0000-4000-8000-000000000000.1800000000000"
    private val highId = "9f000000-0000-4000-8000-000000000000.1800000000000"

    private fun call(
        callId: String,
        direction: CallDirection = CallDirection.OUTGOING,
        status: CallStatus = CallStatus.RINGING,
        peerId: String = peer,
    ) = CallState(callId = callId, peerId = peerId, peerDisplayName = "Astrid", direction = direction, status = status)

    @Test
    fun `no call rings`() {
        assertEquals(OfferVerdict.RING, offerVerdict(null, false, peer, lowId))
    }

    @Test
    fun `a call on its end screen doesn't make the next one busy`() {
        for (status in CallStatus.values().filter { it.isOver }) {
            assertEquals(status.name, OfferVerdict.RING, offerVerdict(call(highId, status = status), true, "someone-else", lowId))
        }
    }

    @Test
    fun `a call going on with someone else is busy`() {
        assertEquals(OfferVerdict.BUSY, offerVerdict(call(highId, peerId = "someone-else"), false, peer, lowId))
        assertEquals(
            OfferVerdict.BUSY,
            offerVerdict(call(highId, direction = CallDirection.INCOMING), false, peer, lowId),
        )
        assertEquals(OfferVerdict.BUSY, offerVerdict(call(highId, status = CallStatus.CONNECTED), true, peer, lowId))
    }

    @Test
    fun `calling each other at once keeps the call with the smaller id on both phones`() {
        // Our call has the higher id: theirs wins, on our phone ...
        assertEquals(OfferVerdict.TAKE_THEIRS, offerVerdict(call(highId), false, peer, lowId))
        assertEquals(
            OfferVerdict.TAKE_THEIRS,
            offerVerdict(call(highId, status = CallStatus.CONNECTING), false, peer, lowId),
        )
        // ... and on theirs, where the roles are swapped.
        assertEquals(OfferVerdict.KEEP_OURS, offerVerdict(call(lowId), false, peer, highId))
    }

    @Test
    fun `an offer after they answered our call is busy`() {
        assertEquals(
            OfferVerdict.BUSY,
            offerVerdict(call(highId, status = CallStatus.CONNECTING), true, peer, lowId),
        )
    }
}
