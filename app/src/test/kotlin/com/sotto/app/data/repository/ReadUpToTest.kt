package com.sotto.app.data.repository

import com.sotto.app.data.local.dao.GroupReadRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadUpToTest {
    @Test
    fun `each member's circle sits under the latest of our messages they read`() {
        val reads = listOf(
            GroupReadRow("bjorn", "m1", 100), GroupReadRow("bjorn", "m3", 300), GroupReadRow("bjorn", "m2", 200),
            GroupReadRow("cecilia", "m1", 100),
            GroupReadRow("dagny", "m3", 300),
        )
        assertEquals(mapOf("m3" to listOf("bjorn", "dagny"), "m1" to listOf("cecilia")), readUpTo(reads))
    }

    @Test
    fun `nobody read anything, no circles`() {
        assertEquals(emptyMap<String, List<String>>(), readUpTo(emptyList()))
    }
}
