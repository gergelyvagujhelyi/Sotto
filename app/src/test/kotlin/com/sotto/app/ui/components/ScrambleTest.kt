package com.sotto.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `the board is quick - long texts still finish within the cap`() {
        assertTrue(Scramble.duration(5_000) <= Scramble.MAX_TOTAL_MS)
        assertTrue(Scramble.MAX_TOTAL_MS <= 450)
        assertTrue(Scramble.duration(20) <= 400) // a typical message
        assertTrue(Scramble.duration(1) > Scramble.LEAD_MS)
        assertTrue(Scramble.duration(0) > 0)
    }

    @Test
    fun `the last flip lands on the real character just as it settles`() {
        val n = 8
        val settle = Scramble.settleAt(3, n)
        val last = Scramble.flapAt("k", 3, n, settle - 1)!!
        assertEquals("k", last.to)
        assertTrue(last.progress > 0.9f)
        assertNull(Scramble.flapAt("k", 3, n, settle))
    }

    @Test
    fun `board letters are reached by flipping forward through the letters before them`() {
        assertEquals("D", Scramble.glyph("E", 0, 1))
        assertEquals("C", Scramble.glyph("E", 0, 2))
        assertEquals("d", Scramble.glyph("d", 0, 0))
        assertEquals("C", Scramble.glyph("d", 0, 1)) // lower case: through its capital's run
        assertEquals("9", Scramble.glyph("A", 0, 1)) // wraps round the drum
    }

    @Test
    fun `each flap shows the next letter of the run, and the run moves on flap by flap`() {
        val n = 4
        val settle = Scramble.settleAt(2, n)
        val flaps = (0 until settle step 5).mapNotNull { Scramble.flapAt("Q", 2, n, it) }
        assertTrue(flaps.isNotEmpty())
        for ((a, b) in flaps.zipWithNext()) {
            if (a.to == b.to) {
                assertTrue("progress runs forward within a flap", b.progress > a.progress)
            } else {
                assertEquals("the next flap starts from where the last one ended", a.to, b.from)
            }
        }
        assertTrue(flaps.all { it.progress in 0f..1f })
    }

    @Test
    fun `characters off the board flip through board letters, then land`() {
        val n = 3
        val early = Scramble.flapAt("é", 1, n, 0)
        assertNotNull(early)
        assertTrue(early!!.from in Scramble.GLYPHS && early.to in Scramble.GLYPHS)
        assertEquals("😀", Scramble.flapAt("😀", 1, n, Scramble.settleAt(1, n) - 1)!!.to)
    }
}
