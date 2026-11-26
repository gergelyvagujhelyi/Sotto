package com.sotto.app.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundChannelIdsTest {
    private val messages = SoundChannelIds.MESSAGES
    private val groups = SoundChannelIds.GROUPS

    @Test
    fun `an install from before sounds keeps its messages channel`() {
        val existing = listOf("messages_v2", "missed_calls", "background_connection", "incoming_calls_v2")
        assertEquals("messages_v2", messages.current(existing))
        // Groups had no channel of their own: they get their first one.
        assertEquals("groups_v1", groups.current(existing))
    }

    @Test
    fun `a new install starts at the first generation`() {
        assertEquals("messages_v2", messages.current(emptyList()))
        assertEquals("groups_v1", groups.current(emptyList()))
    }

    @Test
    fun `each new sound goes on the next id, never one used before`() {
        assertEquals("messages_v3", messages.next("messages_v2"))
        assertEquals("messages_v12", messages.next("messages_v11"))
        assertEquals("groups_v2", groups.next("groups_v1"))
    }

    @Test
    fun `the newest generation is the one in use`() {
        // Numbers, not text: v10 is newer than v9.
        assertEquals("messages_v10", messages.current(listOf("messages_v9", "messages_v10")))
    }

    @Test
    fun `older generations are stale, other channels are not`() {
        val existing = listOf("messages_v2", "messages_v3", "groups_v1", "missed_calls", "messages")
        assertEquals(listOf("messages_v2"), messages.stale(existing, keep = "messages_v3"))
        assertEquals(emptyList<String>(), groups.stale(existing, keep = "groups_v1"))
    }

    @Test
    fun `ids that only look alike aren't taken for a generation`() {
        // "messages" is the legacy channel, deleted at start; v1 was never Sotto's.
        assertEquals(null, messages.generation("messages"))
        assertEquals(null, messages.generation("messages_v1"))
        assertEquals(null, messages.generation("messages_vx"))
        assertEquals("messages_v2", messages.current(listOf("messages", "messages_v1")))
    }
}
