package com.sotto.app.data.repository

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveRetriesTest {
    /** The phone's storage, kept between "processes" (new ReceiveRetries instances). */
    private class FakePrefs : SharedPreferences {
        val data = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = HashMap<String, Any?>()
            val removes = HashSet<String>()
            var clearing = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { clearing = true }
            // As Android does: clear() first, then the removes and puts of the same edit.
            override fun commit(): Boolean {
                if (clearing) data.clear()
                removes.forEach { data.remove(it) }
                data.putAll(puts)
                return true
            }
            override fun apply() { commit() }
        }
    }

    @Test
    fun `tries survive a new process, and are forgotten once cleared`() {
        val prefs = FakePrefs()
        ReceiveRetries.over(prefs).apply { bump("d:m1"); bump("d:m1") }
        // Samsung stopped Sotto: a new process reads the count from the phone.
        val next = ReceiveRetries.over(prefs)
        assertEquals(2, next.count("d:m1"))
        assertEquals(3, next.bump("d:m1"))
        next.clear("d:m1")
        assertEquals(0, ReceiveRetries.over(prefs).count("d:m1"))
    }

    @Test
    fun `a wipe forgets every count, also in a new process`() {
        val prefs = FakePrefs()
        ReceiveRetries.over(prefs).apply {
            bump("d:m1")
            bump("p:m2")
            clearAll()
            assertEquals(0, count("d:m1"))
        }
        assertEquals(0, ReceiveRetries.over(prefs).count("p:m2"))
    }

    @Test
    fun `tries last counted over a week ago are pruned, fresh ones kept`() {
        val prefs = FakePrefs()
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        ReceiveRetries.over(prefs).apply {
            bump("d:old", now = now - 8 * day)
            bump("d:new", now = now - day)
            prune(now)
        }
        val next = ReceiveRetries.over(prefs)
        assertEquals(0, next.count("d:old"))
        assertEquals(1, next.count("d:new"))
    }
}
