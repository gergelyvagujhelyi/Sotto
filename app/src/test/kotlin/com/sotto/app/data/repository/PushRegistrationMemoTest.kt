package com.sotto.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationMemoTest {
    private val memo = PushRegistrationMemo()

    @Test
    fun `the same account and installation register once per process`() {
        assertFalse(memo.isRegistered("user-1", "fid-a"))
        memo.remember("user-1", "fid-a")
        // FCM's onRegistered right after the app start's own registration: skipped.
        assertTrue(memo.isRegistered("user-1", "fid-a"))
    }

    @Test
    fun `a new installation id or a new account registers again`() {
        memo.remember("user-1", "fid-a")
        assertFalse("new installation id", memo.isRegistered("user-1", "fid-b"))
        assertFalse("new account after a wipe", memo.isRegistered("user-2", "fid-a"))
    }

    @Test
    fun `a wipe forgets the registration`() {
        memo.remember("user-1", "fid-a")
        memo.forget()
        assertFalse(memo.isRegistered("user-1", "fid-a"))
    }
}
