package com.sotto.app.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DirectFetchTest {
    private var jobsQueued = 0
    private val queueJob: () -> Unit = { jobsQueued++ }

    @Test
    fun `a push that fetches in time queues no job`() = runTest {
        var fetches = 0
        val result = fetchOrQueueJob(DIRECT_FETCH_BUDGET_MS, fetch = { fetches++; delay(1_500) }, queueJob = queueJob)
        assertEquals(DirectFetch.Done, result)
        assertEquals(1, fetches)
        assertEquals(0, jobsQueued)
        assertNull(handOverMessage(result, DIRECT_FETCH_BUDGET_MS))
    }

    @Test
    fun `a fetch that runs out of time hands over to the job, and says so`() = runTest {
        // A slow start, such as a fresh install on a phone's background cores (0.6.1).
        val result = fetchOrQueueJob(DIRECT_FETCH_BUDGET_MS, fetch = { delay(DIRECT_FETCH_BUDGET_MS + 1) }, queueJob = queueJob)
        assertEquals(DirectFetch.OutOfTime, result)
        assertEquals(1, jobsQueued)
        assertEquals("Fetch on push ran out of its 8000 ms; the job takes over", handOverMessage(result, DIRECT_FETCH_BUDGET_MS))
    }

    @Test
    fun `a failed fetch hands over to the job, and the log names the error`() = runTest {
        // The job waits for a network, so it can succeed where this attempt failed.
        val error = IOException("no network yet")
        val result = fetchOrQueueJob(DIRECT_FETCH_BUDGET_MS, fetch = { throw error }, queueJob = queueJob)
        // The error itself, though not the same object: coroutines copy an exception crossing
        // into the caller to add to its stack trace.
        val failed = result as DirectFetch.Failed
        assertTrue(failed.error is IOException)
        assertEquals("no network yet", failed.error.message)
        assertEquals(1, jobsQueued)
        assertEquals(
            "Fetch on push failed (IOException: no network yet); the job takes over",
            handOverMessage(result, DIRECT_FETCH_BUDGET_MS),
        )
    }

    @Test
    fun `a cancelled caller isn't mistaken for a failed fetch`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { fetchOrQueueJob(DIRECT_FETCH_BUDGET_MS, fetch = { throw CancellationException("stopped") }, queueJob = queueJob) }
        }
        assertEquals(0, jobsQueued)
    }

    @Test
    fun `the budget stays well inside the time Firebase lets the push handler run`() {
        // Half leaves room for the rest of the handler and the hand-over to the job.
        assertTrue(DIRECT_FETCH_BUDGET_MS <= FIREBASE_HANDLER_LIMIT_MS / 2)
    }
}
