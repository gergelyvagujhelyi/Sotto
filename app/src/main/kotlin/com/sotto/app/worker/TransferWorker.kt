package com.sotto.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sotto.app.R
import com.sotto.app.domain.repository.MessageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves one file (or HD photo copy) to or from the relay's store in the background
 * (ADR-29): it carries on when the user leaves the chat or the app, survives the process
 * being killed (WorkManager runs it again, and the transfer resumes where it was), and
 * shows its progress in a notification while it runs.
 */
@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageRepository: MessageRepository,
) : CoroutineWorker(context, params) {

    private val messageId get() = inputData.getString(KEY_MESSAGE).orEmpty()
    private val upload get() = inputData.getBoolean(KEY_UP, true)

    override suspend fun doWork(): Result = coroutineScope {
        if (messageId.isEmpty()) return@coroutineScope Result.failure()
        // From the background Android may refuse a foreground service; the work still runs.
        runCatching { setForeground(foregroundInfo(null)) }
        val progress = launch {
            var last = -1
            messageRepository.fileProgress.collectLatest { all ->
                val p = all.entries.firstOrNull { it.key.startsWith(messageId) }?.value ?: return@collectLatest
                val percent = (p * 100).toInt()
                if (percent != last && (percent - last >= 5 || percent == 100)) {
                    last = percent
                    runCatching { setForeground(foregroundInfo(percent)) }
                }
            }
        }
        try {
            if (upload) messageRepository.finishSend(messageId) else messageRepository.downloadFile(messageId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Transfer failed (attempt ${runAttemptCount + 1}): ${e.message}")
            // The relay said no for good (no storage, too large, allowance used up): no retries.
            val final = e is com.sotto.app.data.remote.ApiHttpException && e.status in PERMANENT
            if (!final && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                if (upload) messageRepository.markSendFailed(messageId) else messageRepository.markDownloadFailed(messageId)
                Result.failure()
            }
        } finally {
            progress.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(percent: Int?): ForegroundInfo {
        val context = applicationContext
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("File transfers")
                .setDescription("Files being sent or downloaded")
                .build(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (upload) "Sending a file" else "Downloading a file")
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val id = NOTIFICATION_BASE + (messageId.hashCode() and 0xffff)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        private const val TAG = "TransferWorker"
        private const val KEY_MESSAGE = "message"
        private const val KEY_UP = "up"
        private const val CHANNEL = "file_transfers"
        private const val NOTIFICATION_BASE = 0x0F000
        private const val MAX_RETRIES = 4
        private val PERMANENT = setOf(400, 403, 404, 413, 429, 503, 507)

        fun workName(messageId: String, up: Boolean) = "transfer-${if (up) "up" else "down"}-$messageId"
    }
}

/** Queues file transfers as [TransferWorker]s, one per message and direction. */
@Singleton
class FileTransfers @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun upload(messageId: String) = enqueue(messageId, up = true)
    fun download(messageId: String) = enqueue(messageId, up = false)

    private fun enqueue(messageId: String, up: Boolean) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf("message" to messageId, "up" to up))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TransferWorker.workName(messageId, up), ExistingWorkPolicy.KEEP, request)
    }

    /** Whether a transfer for [messageId] is queued or running (blocking: call off the main thread). */
    fun isQueued(messageId: String, up: Boolean): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(TransferWorker.workName(messageId, up)).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
    }.getOrDefault(false)
}
