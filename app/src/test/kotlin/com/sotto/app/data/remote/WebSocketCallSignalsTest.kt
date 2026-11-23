package com.sotto.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WebSocketCallSignalsTest {

    /**
     * The app must take every call signal the relay forwards. 0.7.10 added call_verify to the
     * relay and the call code but not to the socket's list, so it was dropped unread.
     */
    @Test
    fun `the socket takes exactly the call signals the relay forwards`() {
        val source = listOf("../server/app/services/call_signals.py", "server/app/services/call_signals.py")
            .map(::File).first { it.exists() }.readText()
        val block = source.substringAfter("ALLOWED_SIGNAL_TYPES = frozenset({").substringBefore("})")
        val relay = Regex("\"(call_[a-z_]+)\"").findAll(block.lines().filterNot { it.trim().startsWith("#") }.joinToString("\n"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(relay, CALL_SIGNAL_TYPES)
    }
}
