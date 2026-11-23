package com.sotto.app.call

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyOnConnectTest {

    @After
    fun tearDown() = VerifyOnConnect.clear()

    @Test
    fun `a call to them soon after opens the check, once`() {
        VerifyOnConnect.arm("astrid", nowMs = 1_000)
        assertTrue(VerifyOnConnect.take("astrid", nowMs = 30_000))
        assertFalse(VerifyOnConnect.take("astrid", nowMs = 31_000))
    }

    @Test
    fun `a call to someone else, or much later, doesn't`() {
        VerifyOnConnect.arm("astrid", nowMs = 0)
        assertFalse(VerifyOnConnect.take("lars", nowMs = 1_000))

        VerifyOnConnect.arm("astrid", nowMs = 0)
        assertFalse(VerifyOnConnect.take("astrid", nowMs = 3 * 60_000))
    }

    @Test
    fun `nothing armed, nothing opens`() {
        assertFalse(VerifyOnConnect.take("astrid"))
    }
}
