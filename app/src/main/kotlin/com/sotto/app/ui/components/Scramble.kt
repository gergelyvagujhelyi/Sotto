package com.sotto.app.ui.components

/**
 * The departure-board effect of a just-sent message: every character flips through
 * "encrypted" glyphs, then settles into the real one, left to right. Whitespace never
 * flips, so the words keep their shape. Pure, so it can be unit tested.
 */
internal object Scramble {
    const val GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#%&*+=<>/\\|"
    /** How often a flipping character shows a new glyph. */
    const val FLIP_MS = 55L
    /** Everything flips for a moment before the first character settles. */
    const val LEAD_MS = 120L
    /** The last character has settled by then, however long the text. */
    const val MAX_TOTAL_MS = 800L
    private const val PER_CHAR_MS = 30L

    /** When character [index] of a text of [length] characters settles, in ms from the start. */
    fun settleAt(index: Int, length: Int): Long {
        val n = length.coerceAtLeast(1)
        val step = minOf(PER_CHAR_MS.toDouble(), (MAX_TOTAL_MS - LEAD_MS).toDouble() / n)
        return LEAD_MS + ((index + 1) * step).toLong()
    }

    /** How long the effect runs for a text of [length] characters. */
    fun duration(length: Int): Long = settleAt(length.coerceAtLeast(1) - 1, length)

    /** The glyph character [index] shows at [elapsedMs], before it settles; whitespace stays. */
    fun glyphAt(real: Char, index: Int, elapsedMs: Long): Char {
        if (real.isWhitespace()) return real
        val frame = elapsedMs / FLIP_MS
        var h = index * 0x2F0B_3A49 + frame.toInt() * 0x1B87_3593 + 0x5BD1_E995
        h = h xor (h ushr 15)
        h *= 0x2C1B_3C6D
        h = h xor (h ushr 12)
        return GLYPHS[Math.floorMod(h, GLYPHS.length)]
    }
}
