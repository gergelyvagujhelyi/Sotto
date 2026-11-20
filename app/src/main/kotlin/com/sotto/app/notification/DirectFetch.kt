package com.sotto.app.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** How long a push's own fetch may take before the background job takes over. */
internal const val DIRECT_FETCH_BUDGET_MS = 8_000L

/**
 * Run [fetch] within [budgetMs]: true when it finished, false when it failed or ran out of
 * time, and the caller should leave the fetch to the background job.
 *
 * A fetch cut off here loses nothing: each message is stored before the next one starts, and
 * the job's fetch confirms the stored ones without notifying again.
 */
internal suspend fun fetchWithin(budgetMs: Long, fetch: suspend () -> Unit): Boolean = try {
    withTimeout(budgetMs) { fetch() }
    true
} catch (e: TimeoutCancellationException) {
    false
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}
