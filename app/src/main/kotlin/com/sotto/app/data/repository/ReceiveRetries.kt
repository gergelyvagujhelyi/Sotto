package com.sotto.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How often a fetched message couldn't be taken yet (decrypt failed, post-quantum signature
 * missing), kept on the phone: after a few tries the message is given up on (and, for a decrypt
 * failure, the sender asked for a new session). Counted in memory alone, it started again with
 * every process, and a phone whose battery saver stops Sotto between pushes (Samsung) never
 * got to the limit: it held every message from that sender, unread and unacknowledged, forever.
 */
@Singleton
class ReceiveRetries private constructor(private val prefsSource: (() -> SharedPreferences)?) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this({ context.getSharedPreferences("receive_retries", Context.MODE_PRIVATE) })

    private val prefs by lazy { prefsSource?.invoke() }
    private val memory = ConcurrentHashMap<String, Int>()

    /** One more try for [key]; the count so far, this one included. */
    @Synchronized
    fun bump(key: String, now: Long = System.currentTimeMillis()): Int {
        val n = count(key) + 1
        memory[key] = n
        prefs?.edit { putString(key, "$n|$now") }
        return n
    }

    fun count(key: String): Int =
        memory[key] ?: prefs?.getString(key, null)?.substringBefore('|')?.toIntOrNull() ?: 0

    @Synchronized
    fun clear(key: String) {
        memory.remove(key)
        prefs?.edit { remove(key) }
    }

    /** Forgets tries last counted more than a week ago (the relay keeps a message 28 days). */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        val p = prefs ?: return
        val stale = p.all.filter { (_, v) -> ((v as? String)?.substringAfter('|')?.toLongOrNull() ?: 0) < now - KEEP_MS }.keys
        if (stale.isEmpty()) return
        p.edit { stale.forEach { remove(it) } }
        stale.forEach { memory.remove(it) }
    }

    companion object {
        private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

        /** Without storage, for tests. */
        fun inMemory() = ReceiveRetries(null)
    }
}
