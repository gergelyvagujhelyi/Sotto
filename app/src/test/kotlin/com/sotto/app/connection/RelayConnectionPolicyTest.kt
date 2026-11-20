package com.sotto.app.connection

import com.sotto.app.call.NetworkChangeTracker
import com.sotto.app.call.NetworkEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayConnectionPolicyTest {
    @Test
    fun `the socket closes only when nothing needs it`() {
        for (onScreen in listOf(true, false)) for (background in listOf(true, false)) for (call in listOf(true, false)) {
            assertEquals(onScreen || background || call, keepRelaySocket(onScreen, background, call))
        }
        assertEquals(false, keepRelaySocket(onScreen = false, backgroundConnectionOn = false, callActive = false))
    }

    @Test
    fun `the socket moves after Wi-Fi to mobile and after the network comes back, not at the first network`() {
        val tracker = NetworkChangeTracker()
        val wifi = 100L
        val mobile = 200L
        assertEquals(false, reconnectAfter(tracker.update(wifi))) // the first network seen
        assertEquals(true, reconnectAfter(tracker.update(mobile))) // Wi-Fi to mobile
        assertEquals(false, reconnectAfter(tracker.update(null))) // airplane mode: nothing to move to
        assertEquals(true, reconnectAfter(tracker.update(mobile))) // back
        assertEquals(false, reconnectAfter(tracker.update(mobile))) // the same network again
        assertEquals(false, reconnectAfter(NetworkEvent.NONE))
    }
}
