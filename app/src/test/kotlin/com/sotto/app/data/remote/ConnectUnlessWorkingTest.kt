package com.sotto.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectUnlessWorkingTest {

    @Test
    fun `a connection being made right now is left to finish`() {
        // A push that started the app arrives while the app's own start is connecting.
        assertFalse(shouldReconnect(SocketState.CONNECTING, sinceStateMs = 800, sinceFrameMs = 60_000))
        assertFalse(shouldReconnect(SocketState.CONNECTING, sinceStateMs = CONNECT_GRACE_MS, sinceFrameMs = 60_000))
        // One that has taken too long is presumed stuck.
        assertTrue(shouldReconnect(SocketState.CONNECTING, sinceStateMs = CONNECT_GRACE_MS + 1, sinceFrameMs = 60_000))
    }

    @Test
    fun `an open socket the relay spoke on recently is kept, a quiet one replaced`() {
        assertFalse(shouldReconnect(SocketState.OPEN, sinceStateMs = 120_000, sinceFrameMs = 24_000))
        assertFalse(shouldReconnect(SocketState.OPEN, sinceStateMs = 120_000, sinceFrameMs = QUIET_MS))
        // A frozen app's socket, or one on a route that went away, hears nothing for longer.
        assertTrue(shouldReconnect(SocketState.OPEN, sinceStateMs = 120_000, sinceFrameMs = QUIET_MS + 1))
    }

    @Test
    fun `waiting out a retry delay always connects now`() {
        assertTrue(shouldReconnect(SocketState.IDLE, sinceStateMs = 0, sinceFrameMs = 0))
    }

    @Test
    fun `screens skip their poll only while the socket is open and the relay spoke lately`() {
        assertTrue(socketIsLive(SocketState.OPEN, sinceFrameMs = 20_000))
        assertTrue(socketIsLive(SocketState.OPEN, sinceFrameMs = QUIET_MS))
        // Quiet too long: maybe dead, so poll as before.
        assertFalse(socketIsLive(SocketState.OPEN, sinceFrameMs = QUIET_MS + 1))
        // Not open: the poll is all there is.
        assertFalse(socketIsLive(SocketState.CONNECTING, sinceFrameMs = 0))
        assertFalse(socketIsLive(SocketState.IDLE, sinceFrameMs = 0))
    }
}
