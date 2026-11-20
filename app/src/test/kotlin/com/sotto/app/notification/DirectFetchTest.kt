package com.sotto.app.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DirectFetchTest {

    @Test
    fun `a fetch that finishes in time is done, and no job is needed`() = runTest {
        var calls = 0
        assertTrue(fetchWithin(DIRECT_FETCH_BUDGET_MS) { calls++; delay(1_500) })
        assertEquals(1, calls)
    }

    @Test
    fun `a fetch that runs out of time is left to the job`() = runTest {
        // A slow start, such as a fresh install on a phone's background cores (0.6.1).
        assertFalse(fetchWithin(DIRECT_FETCH_BUDGET_MS) { delay(DIRECT_FETCH_BUDGET_MS + 1) })
    }

    @Test
    fun `a failed fetch is left to the job`() = runTest {
        assertFalse(fetchWithin(DIRECT_FETCH_BUDGET_MS) { throw IOException("no network yet") })
    }

    @Test
    fun `a cancelled caller isn't mistaken for a failed fetch`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { fetchWithin(DIRECT_FETCH_BUDGET_MS) { throw CancellationException("stopped") } }
        }
    }

    @Test
    fun `the budget stays well inside the time Android gives a push`() {
        // Android 17 cut a background-woken app's network about 25 s after it started.
        assertTrue(DIRECT_FETCH_BUDGET_MS <= 10_000)
    }
}
