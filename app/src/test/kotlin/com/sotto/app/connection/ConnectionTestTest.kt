package com.sotto.app.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTestTest {
    @Test
    fun `an outcome counts what worked and how long it took`() {
        val o = ConnectionTest.Outcome(total = 5, millis = listOf(200, 180, 2_100))
        assertEquals(3, o.ok)
        assertEquals(200L, o.median)
        assertEquals(2_100L, o.slowest)
    }

    @Test
    fun `an even count's median is the mean of the two middle times`() {
        val o = ConnectionTest.Outcome(total = 5, millis = listOf(100, 3_000))
        assertEquals(1_550L, o.median)
        assertEquals(3_000L, o.slowest)
    }

    @Test
    fun `nothing worked, no times`() {
        val o = ConnectionTest.Outcome(total = 20, millis = emptyList())
        assertEquals(0, o.ok)
        assertNull(o.median)
        assertNull(o.slowest)
    }
}
