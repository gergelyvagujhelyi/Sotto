package com.sotto.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private fun links(text: String) = findLinks(text).map { text.substring(it.range) to it.url }

    @Test
    fun `web addresses become links`() {
        assertEquals(
            listOf("https://example.se/a?b=1#c" to "https://example.se/a?b=1#c"),
            links("look: https://example.se/a?b=1#c"),
        )
        assertEquals(listOf("http://x.se" to "http://x.se"), links("http://x.se"))
        assertEquals(listOf("HTTPS://X.SE" to "HTTPS://X.SE"), links("HTTPS://X.SE now"))
    }

    @Test
    fun `www addresses open over https`() {
        assertEquals(listOf("www.sl.se" to "https://www.sl.se"), links("check www.sl.se"))
    }

    @Test
    fun `punctuation after a link stays outside it`() {
        assertEquals(listOf("https://a.se/x"), links("Go to https://a.se/x.").map { it.first })
        assertEquals(listOf("https://a.se", "www.b.se"), links("https://a.se, www.b.se!").map { it.first })
        assertEquals(listOf("https://a.se/x"), links("(see https://a.se/x)").map { it.first })
        assertEquals(listOf("https://a.se"), links("\"https://a.se\"").map { it.first })
        assertEquals(listOf("https://a.se/x"), links("'https://a.se/x'").map { it.first })
    }

    @Test
    fun `brackets that belong to the link stay in it`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Fika_(tradition)"),
            links("(read https://en.wikipedia.org/wiki/Fika_(tradition))").map { it.first },
        )
    }

    @Test
    fun `invite links keep their fragment`() {
        val invite = "https://relay.example.se/invite#c=abc123&s=xyz"
        assertEquals(listOf(invite), links("Join me: $invite").map { it.first })
    }

    @Test
    fun `bare addresses become links`() {
        assertEquals(listOf("444.hu" to "https://444.hu"), links("444.hu"))
        assertEquals(listOf("444.hu" to "https://444.hu"), links("Read 444.hu."))
        assertEquals(listOf("index.hu/belfold/2026" to "https://index.hu/belfold/2026"), links("see index.hu/belfold/2026!"))
        assertEquals(listOf("sl.se", "example.com"), links("sl.se or example.com").map { it.first })
        assertEquals(listOf("news.bbc.co.uk"), links("(news.bbc.co.uk)").map { it.first })
        assertEquals(listOf("SVT.SE"), links("SVT.SE").map { it.first })
        assertEquals(listOf("localhost.se:8080/x"), links("localhost.se:8080/x").map { it.first })
    }

    @Test
    fun `file names, emails and missing spaces stay plain`() {
        val none = emptyList<Pair<String, String>>()
        assertEquals(none, links("notes.txt, song.mp3, README.md, main.py, photo.jpg"))
        assertEquals(none, links("mail anna@444.hu or anna.berg@sl.se"))
        assertEquals(none, links("Bye.So long, version 1.2.3, 3.14, e.g. this"))
        assertEquals(none, links("Node.js and ok.Then"))
    }

    @Test
    fun `a bare address inside a full link is found once`() {
        assertEquals(
            listOf("https://444.hu/x" to "https://444.hu/x", "www.sl.se" to "https://www.sl.se"),
            links("https://444.hu/x www.sl.se"),
        )
    }

    @Test
    fun `things that only look like links are left alone`() {
        assertEquals(emptyList<Pair<String, String>>(), links("notes.txt, e.g. this"))
        assertEquals(emptyList<Pair<String, String>>(), links("www. and https:// alone"))
        assertEquals(emptyList<Pair<String, String>>(), links("xhttps://a.se"))
        assertEquals(emptyList<Pair<String, String>>(), links("plain text"))
    }

    @Test
    fun `several links on several lines`() {
        assertEquals(
            listOf("https://a.se", "https://b.se/q"),
            links("first https://a.se\nthen https://b.se/q").map { it.first },
        )
    }
}
