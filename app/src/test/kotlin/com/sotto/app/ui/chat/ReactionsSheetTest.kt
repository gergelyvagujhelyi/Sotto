package com.sotto.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionsSheetTest {
    @Test
    fun `the user's own first, then the most given emoji, then by name`() {
        val sorted = sortedReactions(
            listOf(
                ReactionEntry("Sven", "s", "😂", isMine = false),
                ReactionEntry("Astrid", "a", "❤️", isMine = false),
                ReactionEntry("Me", "me", "😂", isMine = true),
                ReactionEntry("Björn", "b", "❤️", isMine = false),
                ReactionEntry("Cecilia", "c", "❤️", isMine = false),
            ),
        )
        assertEquals(listOf("Me", "Astrid", "Björn", "Cecilia", "Sven"), sorted.map { it.name })
    }
}
