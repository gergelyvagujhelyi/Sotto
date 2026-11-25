package com.sotto.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sotto.app.data.repository.GroupRepositoryImpl
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends what waits in the group outbox (ADR-32): copies queued or sealed when the app was
 * stopped, and roster, invitation and answer copies that failed. Runs when there's a network,
 * and again (backing off) while some still wait.
 */
@HiltWorker
class GroupOutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val groups: GroupRepositoryImpl,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val waiting = try {
            groups.resumeInterrupted()
            groups.flushOutbox()
        } catch (e: Exception) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("GroupOutbox", "Flush failed: ${e.message}")
            true
        }
        return if (waiting && runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "group-outbox"
        private const val MAX_RUNS = 12
    }
}

@Singleton
class GroupOutboxScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<GroupOutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            // A run that's waiting keeps its place; one already running gets a follow-up.
            WorkManager.getInstance(context).enqueueUniqueWork(GroupOutboxWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
