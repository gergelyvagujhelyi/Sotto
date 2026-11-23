package com.sotto.app.util

/**
 * Release-visible diagnostic lines (never keys, names or message content). Outside Android, in
 * the JVM unit tests, android.util.Log isn't there: the line is skipped instead of failing.
 */
object Diag {
    fun i(tag: String, message: String) {
        try {
            android.util.Log.i(tag, message)
        } catch (_: RuntimeException) {
            // Unit tests: Log isn't mocked.
        }
    }
}
