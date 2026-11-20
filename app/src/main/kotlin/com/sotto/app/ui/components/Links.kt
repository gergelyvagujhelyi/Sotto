package com.sotto.app.ui.components

/** A web link found in a message: where it sits in the text, and the address to open. */
internal data class FoundLink(val range: IntRange, val url: String)

/**
 * Finds the web links in a message: anything starting with `http://`, `https://`
 * or `www.`. Bare domains ("example.com") aren't links, so file names and
 * abbreviations stay plain text. Punctuation that ends a sentence, and a closing
 * bracket or quote with no opening one inside the link, stay outside it.
 */
internal fun findLinks(text: String): List<FoundLink> =
    LinkStart.findAll(text).mapNotNull { match ->
        val raw = trimTrailing(match.value)
        val prefix = match.groupValues[1]
        // "www." or "https://" with nothing after it isn't a link.
        if (raw.length <= prefix.length || !raw[prefix.length].isLetterOrDigit()) return@mapNotNull null
        val url = if (prefix.equals("www.", ignoreCase = true)) "https://$raw" else raw
        FoundLink(match.range.first until match.range.first + raw.length, url)
    }.toList()

// Starts at a word boundary, so "xhttps://" in the middle of a word isn't taken.
private val LinkStart = Regex("""(?i)(?<![\p{L}\p{N}_])(https?://|www\.)[^\s<>"]+""")

private const val SentenceEnd = ".,;:!?'*"
private val Closing = mapOf(')' to '(', ']' to '[', '}' to '{')

private fun trimTrailing(link: String): String {
    var end = link.length
    while (end > 0) {
        val c = link[end - 1]
        val opening = Closing[c]
        when {
            c in SentenceEnd -> end--
            // "(see https://en.wikipedia.org/wiki/Foo_(bar))" keeps the inner pair.
            opening != null && link.substring(0, end).count { it == c } > link.substring(0, end).count { it == opening } -> end--
            else -> break
        }
    }
    return link.substring(0, end)
}
