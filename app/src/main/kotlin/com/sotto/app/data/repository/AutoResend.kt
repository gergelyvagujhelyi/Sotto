package com.sotto.app.data.repository

/**
 * Which of our failed messages go again by themselves. Only fresh ones: a message that
 * couldn't go out within [WINDOW_MS] of being written waits for a tap, so nothing stale
 * arrives a day or more later. A group copy whose member's keys don't match the vouched ones
 * waits too: sending again can't fix that. One whose keys couldn't be fetched yet goes again
 * (the relay was out of reach, as for any other failure).
 */
internal object AutoResend {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    /** Tries per group copy; a one-to-one message is bounded by ResendWorker's runs instead. */
    const val MAX_COPY_ATTEMPTS = 12

    fun messageDue(writtenAt: Long, now: Long): Boolean = now - writtenAt in 0..WINDOW_MS

    fun copyDue(messageId: String?, attempts: Int, lastError: String?, createdAt: Long, now: Long): Boolean =
        messageId != null &&
            attempts < MAX_COPY_ATTEMPTS &&
            lastError != GroupRepositoryImpl.ERROR_KEYS &&
            messageDue(createdAt, now)
}
