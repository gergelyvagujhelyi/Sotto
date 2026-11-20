package com.sotto.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrambleTest {
    @Test
    fun `characters settle left to right after a short lead`() {
        val n = 12
        val times = (0 until n).map { Scramble.settleAt(it, n) }
        assertTrue(times.first() > Scramble.LEAD_MS)
        assertEquals(times.sorted(), times)
        assertEquals(Scramble.duration(n), times.last())
    }

    @Test
    fun `long texts still finish within the cap`() {
        assertTrue(Scramble.duration(5_000) <= Scramble.MAX_TOTAL_MS)
        assertTrue(Scramble.duration(1) > Scramble.LEAD_MS)
        assertTrue(Scramble.duration(0) > 0)
    }

    @Test
    fun `whitespace never flips, everything else shows board glyphs`() {
        assertEquals(' ', Scramble.glyphAt(' ', 3, 200))
        assertEquals('\n', Scramble.glyphAt('\n', 3, 200))
        for (i in 0 until 50) for (t in listOf(0L, 60L, 300L)) {
            assertTrue(Scramble.glyphAt('é', i, t) in Scramble.GLYPHS)
        }
    }

    @Test
    fun `a glyph holds for a flip and then changes`() {
        val a = Scramble.glyphAt('x', 5, 0)
        assertEquals(a, Scramble.glyphAt('x', 5, Scramble.FLIP_MS - 1))
        val later = (1..10).map { Scramble.glyphAt('x', 5, it * Scramble.FLIP_MS) }
        assertTrue(later.any { it != a })
    }
}
