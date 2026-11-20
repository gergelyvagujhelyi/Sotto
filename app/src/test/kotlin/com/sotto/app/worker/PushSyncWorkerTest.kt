package com.sotto.app.worker

import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSyncWorkerTest {

    @Test
    fun `a push's fetch is expedited, needs a network and starts at once`() {
        val spec = PushSyncWorker.request().workSpec

        // Expedited: Android keeps the app running, with network access, until the job ends.
        assertTrue(spec.expedited)
        // Out of expedited quota it still runs, as ordinary work.
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(0L, spec.initialDelay)
    }
}
