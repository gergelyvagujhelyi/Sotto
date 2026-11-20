package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkChangeTrackerTest {
    @Test
    fun `the first network seen is not a change`() {
        assertEquals(NetworkEvent.NONE, NetworkChangeTracker().update(100L))
    }

    @Test
    fun `a switch to another network is a change, the same one again is not`() {
        val tracker = NetworkChangeTracker()
        tracker.update(100L)
        assertEquals(NetworkEvent.NONE, tracker.update(100L))
        assertEquals(NetworkEvent.SWITCHED, tracker.update(200L))
        assertEquals(NetworkEvent.NONE, tracker.update(200L))
    }

    @Test
    fun `losing the network is reported once, another network afterwards is a switch`() {
        val tracker = NetworkChangeTracker()
        tracker.update(100L)
        assertEquals(NetworkEvent.LOST, tracker.update(null))
        assertEquals(NetworkEvent.NONE, tracker.update(null))
        assertEquals(NetworkEvent.SWITCHED, tracker.update(200L))
    }

    @Test
    fun `the same network back after a loss is a return`() {
        val tracker = NetworkChangeTracker()
        tracker.update(100L)
        tracker.update(null)
        assertEquals(NetworkEvent.RESTORED, tracker.update(100L))
        assertEquals(NetworkEvent.NONE, tracker.update(100L))
    }

    @Test
    fun `no network at the start, then one, is neither a loss nor a change`() {
        val tracker = NetworkChangeTracker()
        assertEquals(NetworkEvent.NONE, tracker.update(null))
        assertEquals(NetworkEvent.NONE, tracker.update(100L))
    }
}
