package com.sotto.app.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * How long Firebase lets the push handler run: it "should complete within 20 seconds"
 * (firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessagingService).
 * Firebase's code on the phone keeps the phone awake for the handler until then.
 */
internal const val FIREBASE_HANDLER_LIMIT_MS = 20_000L

/**
 * How long a push's own fetch may take before the background job takes over. A normal fetch
 * takes a second or two; this leaves room for a slow phone or network and stays well inside
 * [FIREBASE_HANDLER_LIMIT_MS], and inside the ~25 s Android 17 gave a background-woken app.
 */
internal const val DIRECT_FETCH_BUDGET_MS = 8_000L

/** How a push's own fetch ended. */
internal sealed interface DirectFetch {
    /** The messages are fetched: no job needed. */
    data object Done : DirectFetch

    /** It ran out of its budget: a slow start, such as a fresh install's first fetch. */
    data object OutOfTime : DirectFetch

    /** It failed, for example before the network was up after the phone woke. */
    data class Failed(val error: Exception) : DirectFetch
}

/**
 * Run [fetch] within [budgetMs] and say how it ended.
 *
 * A fetch cut off here loses nothing: each message is stored before the next one starts, and
 * the job's fetch confirms the stored ones without notifying again. A cancelled caller is not
 * a failed fetch and is passed on.
 */
internal suspend fun fetchWithin(budgetMs: Long, fetch: suspend () -> Unit): DirectFetch = try {
    withTimeout(budgetMs) { fetch() }
    DirectFetch.Done
} catch (e: TimeoutCancellationException) {
    DirectFetch.OutOfTime
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DirectFetch.Failed(e)
}

/**
 * A push's handling: fetch at once within [budgetMs], and call [queueJob] (the background
 * job) only if that didn't get the messages. Returns how the fetch ended, for the log.
 */
internal suspend fun fetchOrQueueJob(
    budgetMs: Long,
    fetch: suspend () -> Unit,
    queueJob: () -> Unit,
): DirectFetch {
    val result = fetchWithin(budgetMs, fetch)
    if (result != DirectFetch.Done) queueJob()
    return result
}

/** The log line for a fetch the job takes over; null when it was done. */
internal fun handOverMessage(result: DirectFetch, budgetMs: Long): String? = when (result) {
    DirectFetch.Done -> null
    DirectFetch.OutOfTime -> "Fetch on push ran out of its $budgetMs ms; the job takes over"
    is DirectFetch.Failed ->
        "Fetch on push failed (${result.error::class.simpleName}: ${result.error.message}); the job takes over"
}
