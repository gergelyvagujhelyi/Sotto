package com.sotto.app.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingFetchGateTest {

    @Test
    fun `an idle gate fetches once`() = runTest {
        val gate = PendingFetchGate(backgroundScope)
        var passes = 0

        gate.fetchOrRequest { passes++ }
        gate.fetchAndWait { passes++ }

        assertEquals(2, passes)
    }

    @Test
    fun `a trigger during a fetch returns at once and the fetch goes round once more`() = runTest {
        val gate = PendingFetchGate(backgroundScope)
        val firstPass = CompletableDeferred<Unit>()
        var passes = 0
        val fetch: suspend () -> Unit = {
            passes++
            if (passes == 1) firstPass.await()
        }

        val running = launch { gate.fetchOrRequest(fetch) }
        runCurrent()
        gate.fetchOrRequest(fetch) // returns without fetching: one is running
        assertEquals(1, passes)

        firstPass.complete(Unit)
        running.join()
        assertEquals(2, passes)
    }

    @Test
    fun `fetchAndWait waits for a running fetch, then fetches itself`() = runTest {
        val gate = PendingFetchGate(backgroundScope)
        val firstPass = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var passes = 0
        val fetch: suspend () -> Unit = {
            val pass = ++passes
            events += "start $pass"
            if (pass == 1) firstPass.await()
            events += "end $pass"
        }

        val running = launch { gate.fetchOrRequest(fetch) }
        runCurrent()
        val waiting = async {
            gate.fetchAndWait(fetch)
            events += "returned"
        }
        runCurrent()
        assertFalse("must not return while the other fetch runs", waiting.isCompleted)

        firstPass.complete(Unit)
        waiting.await()
        running.join()
        assertEquals(listOf("start 1", "end 1", "start 2", "end 2", "returned"), events)
    }

    @Test
    fun `a trigger during fetchAndWait's own pass makes it fetch again before returning`() = runTest {
        val gate = PendingFetchGate(backgroundScope)
        val firstPass = CompletableDeferred<Unit>()
        var passes = 0
        val fetch: suspend () -> Unit = {
            passes++
            if (passes == 1) firstPass.await()
        }

        val waiting = async { gate.fetchAndWait(fetch) }
        runCurrent()
        gate.fetchOrRequest(fetch) // a socket signal while the job's fetch runs
        firstPass.complete(Unit)
        waiting.await()

        assertEquals(2, passes)
    }

    @Test
    fun `fetchAndWait throws when the fetch fails, for the job to retry`() = runTest {
        val gate = PendingFetchGate(backgroundScope)
        try {
            gate.fetchAndWait { throw IllegalStateException("relay unreachable") }
            fail("expected the failure to reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("relay unreachable", e.message)
        }
        // The gate is free again afterwards.
        var fetched = false
        gate.fetchAndWait { fetched = true }
        assertTrue(fetched)
    }

    @Test
    fun `a trigger during a failing fetch still gets its own fetch`() = runTest {
        val gate = PendingFetchGate(backgroundScope)
        val firstPass = CompletableDeferred<Unit>()
        var passes = 0
        val fetch: suspend () -> Unit = {
            passes++
            if (passes == 1) {
                firstPass.await()
                throw IllegalStateException("network lost")
            }
        }

        val failing = launch { runCatching { gate.fetchOrRequest(fetch) } }
        runCurrent()
        gate.fetchOrRequest(fetch)
        firstPass.complete(Unit)
        failing.join()
        advanceUntilIdle()

        assertEquals(2, passes)
    }
}
