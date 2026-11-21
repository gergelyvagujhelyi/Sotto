package com.sotto.app.ui.components

/**
 * The departure-board effect of a just-sent message, like a split-flap board at a
 * station or airport: every character flips forward through the board's letters and
 * lands on the real one, left to right. Each flip is a flap folding down over the
 * middle line (see [Flap], drawn by the bubble). Whitespace never flips, so the words
 * keep their shape. Pure, so it can be unit tested.
 */
internal object Scramble {
    /** The board's letters, in the order a flap shows them. */
    const val GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    /** How long one flap takes to fall. */
    const val FLIP_MS = 45L
    /** Everything flips for a moment before the first character settles. */
    const val LEAD_MS = 90L
    /** The last character has settled by then, however long the text. */
    const val MAX_TOTAL_MS = 450L
    private const val PER_CHAR_MS = 15L

    /** When character [index] of a text of [length] characters settles, in ms from the start. */
    fun settleAt(index: Int, length: Int): Long {
        val n = length.coerceAtLeast(1)
        val step = minOf(PER_CHAR_MS.toDouble(), (MAX_TOTAL_MS - LEAD_MS).toDouble() / n)
        return LEAD_MS + ((index + 1) * step).toLong()
    }

    /** How long the effect runs for a text of [length] characters. */
    fun duration(length: Int): Long = settleAt(length.coerceAtLeast(1) - 1, length)

    /** One flip: the flap falls from showing [from] to showing [to]; [progress] goes from 0 to 1. */
    data class Flap(val from: String, val to: String, val progress: Float)

    /**
     * The flip character [index] (whose real text is [real], one code point) of a text of
     * [length] characters is in at [elapsedMs]; null once it has settled. The flips are
     * timed back from the settle time, so the last one lands on [real] just as it settles.
     */
    fun flapAt(real: String, index: Int, length: Int, elapsedMs: Long): Flap? {
        val remaining = settleAt(index, length) - elapsedMs
        if (remaining <= 0) return null
        val flipsAfter = (remaining - 1) / FLIP_MS // 0 during the last flip
        val progress = 1f - ((remaining - 1) % FLIP_MS + 1).toFloat() / FLIP_MS
        return Flap(
            from = glyph(real, index, flipsAfter + 1),
            to = glyph(real, index, flipsAfter),
            progress = progress,
        )
    }

    /** What character [index] shows [flipsBefore] flips before it lands on [real]. */
    internal fun glyph(real: String, index: Int, flipsBefore: Long): String {
        if (flipsBefore <= 0L) return real
        // A board letter is approached through the ones before it (…, A, B for "B"), a
        // lower-case one through its capital's; anything else (accents, emoji) through
        // a run that starts at a different letter for each character.
        val landing = GLYPHS.indexOf(real.uppercase().singleOrNull() ?: ' ')
            .takeIf { it >= 0 }
            ?: Math.floorMod(index * 7 + 3, GLYPHS.length)
        return GLYPHS[Math.floorMod(landing - flipsBefore, GLYPHS.length.toLong()).toInt()].toString()
    }
}
