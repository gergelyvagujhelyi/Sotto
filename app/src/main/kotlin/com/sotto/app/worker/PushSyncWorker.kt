package com.sotto.app.worker

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sotto.app.R
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.notification.ChatNotificationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Fetches the messages a push announced, as an expedited job: Android keeps the app running,
 * with network access, until the job ends, however long a slow phone takes.
 *
 * The push callback used to do this itself, within the few seconds Android gives a push.
 * A freshly installed app on a phone's slow background cores ran past them. Android then
 * froze it and cut its network halfway (the download was done, the confirmation never sent),
 * and the message showed only when the app was opened.
 */
@HiltWorker
class PushSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageRepository: MessageRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        messageRepository.fetchPendingAndWait()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Push-triggered fetch failed (attempt ${runAttemptCount + 1}): ${e.message}")
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    /**
     * Before Android 12, expedited work runs as a foreground service, which needs a notification:
     * the background connection's, silent and out of the status bar.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, ChatNotificationManager.CHANNEL_BACKGROUND_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Checking for messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build(),
    )

    companion object {
        private const val TAG = "PushSyncWorker"
        const val WORK_NAME = "push-sync"
        private const val MAX_RETRIES = 5
        private const val NOTIFICATION_ID = 0x0BC02

        /** One fetch, expedited, run as ordinary work when the app is out of expedited quota. */
        fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        /**
         * Queue a fetch for a push. Appended, not kept: a push that arrives while a fetch job
         * runs gets its own fetch after it, so a message that reached the relay after that
         * job read the list isn't left until the next poll.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }
    }
}
