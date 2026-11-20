package com.sotto.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets one fetch of the relay's pending messages run at a time.
 *
 * A trigger that finds a fetch running (a socket signal, a poll) doesn't start another: it asks
 * the running one to go round once more ([fetchOrRequest]), because the message it announced may
 * have reached the relay after that fetch read the list.
 *
 * Background work that must not end before the messages are in (the job a push starts) waits
 * instead ([fetchAndWait]). Returning at once would end the job while another fetch was still
 * halfway, and Android may then freeze the app or cut its network before that fetch finishes.
 */
internal class PendingFetchGate(private val rerunScope: CoroutineScope) {
    private val lock = Mutex()

    /** Set when a fetch is requested while one is running; the running one goes round again. */
    @Volatile
    private var requestedAgain = false

    /** Fetch now; or, when a fetch is running, make it fetch once more and return at once. */
    suspend fun fetchOrRequest(fetch: suspend () -> Unit) {
        if (!lock.tryLock()) {
            requestedAgain = true
            return
        }
        try {
            fetchUntilNoRequest(fetch)
        } finally {
            lock.unlock()
            // A request that came in while this run was failing, being cancelled, or past its
            // last check returned without fetching: run it here, or the message it announced
            // waits for the next poll (up to 15 minutes).
            if (requestedAgain) {
                rerunScope.launch {
                    try {
                        fetchOrRequest(fetch)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // The next trigger or poll fetches again.
                    }
                }
            }
        }
    }

    /**
     * Wait for a running fetch, then fetch once more. Returns once a fetch that began after this
     * call has finished, so every message that reached the relay before the call is in. A failed
     * fetch throws, for the caller to retry.
     */
    suspend fun fetchAndWait(fetch: suspend () -> Unit) {
        do {
            lock.withLock { fetchUntilNoRequest(fetch) }
            // A request that came in between the last check and the unlock returned without
            // fetching, counting on this run: go round for it too.
        } while (requestedAgain)
    }

    private suspend fun fetchUntilNoRequest(fetch: suspend () -> Unit) {
        do {
            requestedAgain = false
            fetch()
        } while (requestedAgain)
    }
}
