package com.sotto.app.domain.model

/** Rules for names people see: the user's own name, nicknames, and names contacts send. */
object DisplayNames {
    /** Longest name kept, counted in characters as people see them (code points). */
    const val MAX_LENGTH = 40

    /**
     * The name cleaned up for showing, or null when nothing is left. Removes control
     * characters and text-direction overrides (which can make one name look like another),
     * keeps the joiners emoji are built from, collapses runs of whitespace into one space,
     * and cuts the name to [MAX_LENGTH] without splitting a character.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = StringBuilder()
        var count = 0
        var spaceBefore = false
        var i = 0
        while (i < raw.length && count < MAX_LENGTH) {
            val codePoint = raw.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ->
                    spaceBefore = cleaned.isNotEmpty()
                isHidden(codePoint) -> Unit
                else -> {
                    if (spaceBefore) {
                        // No room left for anything after the space: stop without it.
                        if (count + 1 >= MAX_LENGTH) break
                        cleaned.append(' ')
                        count++
                        spaceBefore = false
                    }
                    cleaned.appendCodePoint(codePoint)
                    count++
                }
            }
        }
        return cleaned.toString().ifEmpty { null }
    }

    private fun isHidden(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) ||
            codePoint == 0x200E || codePoint == 0x200F || // left-to-right / right-to-left marks
            codePoint == 0x061C || // Arabic letter mark
            codePoint in 0x202A..0x202E || // direction embeddings and overrides
            codePoint in 0x2066..0x2069 || // direction isolates
            codePoint == 0x200B || codePoint == 0xFEFF || // zero-width space, byte order mark
            codePoint == 0x00AD // soft hyphen
}
