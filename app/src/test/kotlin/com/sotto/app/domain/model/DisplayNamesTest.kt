package com.sotto.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNamesTest {

    @Test
    fun `keeps an ordinary name`() {
        assertEquals("Maja Sjöberg", DisplayNames.sanitize("Maja Sjöberg"))
    }

    @Test
    fun `trims and collapses whitespace, line breaks included`() {
        assertEquals("Maja Sjöberg", DisplayNames.sanitize("  Maja \n\t  Sjöberg  "))
    }

    @Test
    fun `removes control, direction and invisible characters`() {
        assertEquals("Maja Sjöberg", DisplayNames.sanitize("Ma\u0000ja‮⁦ S​jö­berg‏"))
    }

    @Test
    fun `keeps emoji built with joiners`() {
        assertEquals("Anna 👩‍💻", DisplayNames.sanitize("Anna 👩‍💻"))
    }

    @Test
    fun `cuts long names to the limit without splitting a character`() {
        assertEquals("a".repeat(DisplayNames.MAX_LENGTH), DisplayNames.sanitize("a".repeat(50)))
        val emoji = DisplayNames.sanitize("😀".repeat(50))!!
        assertEquals(DisplayNames.MAX_LENGTH, emoji.codePointCount(0, emoji.length))
        assertEquals(DisplayNames.MAX_LENGTH * 2, emoji.length)
    }

    @Test
    fun `does not end on a space when the limit falls there`() {
        val nearlyFull = "a".repeat(DisplayNames.MAX_LENGTH - 1)
        assertEquals(nearlyFull, DisplayNames.sanitize("$nearlyFull bcd"))
        val roomForOneMore = "a".repeat(DisplayNames.MAX_LENGTH - 2)
        assertEquals("$roomForOneMore b", DisplayNames.sanitize("$roomForOneMore bcd"))
    }

    @Test
    fun `nothing visible left means no name`() {
        assertNull(DisplayNames.sanitize(null))
        assertNull(DisplayNames.sanitize(""))
        assertNull(DisplayNames.sanitize("   \n"))
        assertNull(DisplayNames.sanitize("‮​\u0001"))
    }
}
