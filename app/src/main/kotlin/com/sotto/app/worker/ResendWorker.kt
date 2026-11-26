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
import com.sotto.app.domain.repository.MessageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends our failed one-to-one messages again by itself (AutoResend), once there's a network,
 * and again, backing off, while some still fail: a short network drop no longer needs a tap
 * on each message. Group messages go through the group outbox the same way.
 */
@HiltWorker
class ResendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messages: MessageRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val waiting = try {
            messages.resendFailed()
        } catch (e: Exception) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("Resend", "Resend failed: ${e.message}")
            true
        }
        return if (waiting && runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "resend-failed"
        /** 30 s doubling up to WorkManager's 5 h cap: tries over about a day, the resend window. */
        private const val MAX_RUNS = 12
    }
}

@Singleton
class ResendScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * After a failure: a fresh run in 30 s, replacing one that's waiting out a long backoff (or
     * running past this message), so a new failure never inherits hours of waiting.
     */
    fun schedule() = enqueue(ExistingWorkPolicy.REPLACE, initialDelaySeconds = 30)

    /** The relay is reachable again (a fresh socket session): try now, not after the backoff. */
    fun retryNow() = enqueue(ExistingWorkPolicy.REPLACE, initialDelaySeconds = 0)

    private fun enqueue(policy: ExistingWorkPolicy, initialDelaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<ResendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(ResendWorker.WORK_NAME, policy, request) }
    }
}
