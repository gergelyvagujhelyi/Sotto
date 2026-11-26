package com.sotto.app.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReachabilityTest {
    @Test
    fun `a reconnect waits while a message uploads, and goes once it's done`() = runTest {
        val reachability = RelayReachability()
        val uploadDone = CompletableDeferred<Unit>()
        val upload = async { reachability.uploading { uploadDone.await() } }
        advanceTimeBy(10)
        val reconnect = async { reachability.awaitNoUploads(30_000) }
        advanceTimeBy(5_000)
        assertFalse(reconnect.isCompleted)
        uploadDone.complete(Unit)
        upload.await()
        reconnect.await()
        assertTrue(reconnect.isCompleted)
    }

    @Test
    fun `a stuck upload holds a reconnect back 30 s at most`() = runTest {
        val reachability = RelayReachability()
        val never = CompletableDeferred<Unit>()
        val upload = async { reachability.uploading { never.await() } }
        advanceTimeBy(10)
        val reconnect = async { reachability.awaitNoUploads(30_000) }
        advanceTimeBy(30_001)
        assertTrue(reconnect.isCompleted)
        upload.cancel()
    }

    @Test
    fun `with nothing uploading a reconnect doesn't wait`() = runTest {
        val reachability = RelayReachability()
        val reconnect = async { reachability.awaitNoUploads(30_000) }
        advanceTimeBy(1)
        assertTrue(reconnect.isCompleted)
    }
}
