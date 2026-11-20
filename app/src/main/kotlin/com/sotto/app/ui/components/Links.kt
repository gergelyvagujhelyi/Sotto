package com.sotto.app.ui.components

import java.util.Locale

/** A web link found in a message: where it sits in the text, and the address to open. */
internal data class FoundLink(val range: IntRange, val url: String)

/**
 * Finds the web links in a message: anything starting with `http://`, `https://` or
 * `www.`, and bare addresses such as `444.hu` or `example.com/page` whose ending is a
 * real top-level domain ([BareTlds]). File names (`notes.txt`, `song.mp3`) and email
 * addresses aren't links. Punctuation that ends a sentence, and a closing bracket or
 * quote with no opening one inside the link, stay outside it.
 */
internal fun findLinks(text: String): List<FoundLink> {
    val found = LinkStart.findAll(text).mapNotNull { match ->
        val raw = trimTrailing(match.value)
        val prefix = match.groupValues[1]
        // "www." or "https://" with nothing after it isn't a link.
        if (raw.length <= prefix.length || !raw[prefix.length].isLetterOrDigit()) return@mapNotNull null
        val url = if (prefix.equals("www.", ignoreCase = true)) "https://$raw" else raw
        FoundLink(match.range.first until match.range.first + raw.length, url)
    }.toMutableList()
    for (match in BareDomain.findAll(text)) {
        if (found.any { it.range.first <= match.range.last && match.range.first <= it.range.last }) continue
        val tld = match.groupValues[1]
        if (tld.lowercase(Locale.ROOT) !in BareTlds) continue
        // "Bye.So long": a word after a missing space, not a domain. Domains are typed
        // in lower case, sometimes all in capitals.
        if (tld != tld.lowercase(Locale.ROOT) && tld != tld.uppercase(Locale.ROOT)) continue
        val raw = trimTrailing(match.value)
        found += FoundLink(match.range.first until match.range.first + raw.length, "https://$raw")
    }
    return found.sortedBy { it.range.first }
}

// Starts at a word boundary, so "xhttps://" in the middle of a word isn't taken.
private val LinkStart = Regex("""(?i)(?<![\p{L}\p{N}_])(https?://|www\.)[^\s<>"]+""")

/**
 * name.name.tld, then an optional port and path. Not part of a longer word, an email
 * address (`@` on either side) or a path (`/` before it); group 1 is the ending.
 */
private val BareDomain = Regex(
    """(?<![\p{L}\p{N}_@./:-])(?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?\.)+(\p{L}{2,24})""" +
        """(?![\p{L}\p{N}_@-])(?::\d{1,5})?(?:[/?#][^\s<>"]*)?""",
)

/**
 * Endings that make a bare address a link: every country's (`hu`, `se`) and the common
 * generic ones. Not `md`, `py`, `rs` or `sh`, which are far more often file names
 * (README.md, main.py) than websites; those links still work with `https://`.
 */
internal val BareTlds: Set<String> = buildSet {
    Locale.getISOCountries().forEach { add(it.lowercase(Locale.ROOT)) }
    addAll(listOf("uk", "eu", "ac"))
    removeAll(listOf("md", "py", "rs", "sh"))
    addAll(
        listOf(
            "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "name", "pro",
            "app", "dev", "page", "xyz", "online", "site", "website", "store", "shop", "blog",
            "tech", "cloud", "news", "media", "live", "art", "design", "studio", "travel",
            "museum", "jobs", "mobi", "asia", "wiki", "link", "club", "top", "fun", "space",
            "email", "network", "digital", "social", "games", "music", "video", "photo",
            "photos", "cafe", "bar", "restaurant", "hotel", "city", "berlin", "london",
            "paris", "stockholm", "budapest", "nyc", "eus", "cat", "coop", "aero", "tel",
        ),
    )
}

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
