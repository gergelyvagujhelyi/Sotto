package com.sotto.app.ui.chat

import com.sotto.app.domain.model.GroupProgress
import com.sotto.app.domain.model.MessageState
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupTicksTest {
    @Test
    fun `partway delivered shows double ticks with how many have it`() {
        assertEquals(MessageState.DELIVERED to "1/2", groupTicks(MessageState.SENT, GroupProgress(total = 2, delivered = 1, seen = 0)))
        // A member read it but another's phone doesn't have it yet: delivery is what's partway.
        assertEquals(MessageState.DELIVERED to "2/3", groupTicks(MessageState.SENT, GroupProgress(total = 3, delivered = 2, seen = 1)))
    }

    @Test
    fun `everyone has it and some read it shows read ticks with how many read it`() {
        assertEquals(MessageState.SEEN to "1/2", groupTicks(MessageState.DELIVERED, GroupProgress(total = 2, delivered = 2, seen = 1)))
    }

    @Test
    fun `nobody yet, everyone, or a group of two shows the plain ticks`() {
        assertEquals(MessageState.SENT to null, groupTicks(MessageState.SENT, GroupProgress(total = 2, delivered = 0, seen = 0)))
        assertEquals(MessageState.DELIVERED to null, groupTicks(MessageState.DELIVERED, GroupProgress(total = 2, delivered = 2, seen = 0)))
        assertEquals(MessageState.SEEN to null, groupTicks(MessageState.SEEN, GroupProgress(total = 2, delivered = 2, seen = 2)))
        // Only one other member: the ticks already say it all.
        assertEquals(MessageState.SENT to null, groupTicks(MessageState.SENT, GroupProgress(total = 1, delivered = 0, seen = 0)))
        // Sending or failed: no count.
        assertEquals(MessageState.FAILED to null, groupTicks(MessageState.FAILED, GroupProgress(total = 3, delivered = 1, seen = 0)))
        assertEquals(MessageState.SENT to null, groupTicks(MessageState.SENT, null))
    }
}
