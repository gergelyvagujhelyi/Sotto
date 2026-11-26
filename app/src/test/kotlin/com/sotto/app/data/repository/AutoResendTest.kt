package com.sotto.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResendTest {
    private val now = 10L * 24 * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000

    @Test
    fun `a message written within the last day goes again by itself, an older one waits for a tap`() {
        assertTrue(AutoResend.messageDue(writtenAt = now - hour, now = now))
        assertTrue(AutoResend.messageDue(writtenAt = now - 24 * hour, now = now))
        assertFalse(AutoResend.messageDue(writtenAt = now - 25 * hour, now = now))
    }

    @Test
    fun `a group message's failed copy goes again, a few times, unless its keys don't match`() {
        assertTrue(AutoResend.copyDue("m", attempts = 1, lastError = "timeout", createdAt = now - hour, now = now))
        // Keys not fetched yet: the relay was out of reach, like any other failure.
        assertTrue(AutoResend.copyDue("m", attempts = 1, lastError = GroupRepositoryImpl.ERROR_NO_KEYS, createdAt = now - hour, now = now))
        assertFalse(AutoResend.copyDue("m", attempts = 1, lastError = GroupRepositoryImpl.ERROR_KEYS, createdAt = now - hour, now = now))
        assertFalse(AutoResend.copyDue("m", attempts = AutoResend.MAX_COPY_ATTEMPTS, lastError = null, createdAt = now - hour, now = now))
        assertFalse(AutoResend.copyDue("m", attempts = 1, lastError = null, createdAt = now - 25 * hour, now = now))
        // Control packets (no message) have their own rule in the outbox.
        assertFalse(AutoResend.copyDue(null, attempts = 1, lastError = null, createdAt = now - hour, now = now))
    }
}
