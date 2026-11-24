package com.sotto.app.domain.model

import com.sotto.app.R
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyQuoteTest {
    @Test
    fun `a quote of media travels with the English label every Sotto has sent`() {
        assertEquals("📷 Photo", quoteText(MediaKind.PHOTO, ""))
        assertEquals("🎤 Voice message: Szia", quoteText(MediaKind.VOICE, " Szia "))
        assertEquals("📎 File", quoteText(MediaKind.FILE, ""))
        assertEquals("📎 menu.pdf: holnapra", quoteText(MediaKind.FILE, "holnapra", "menu.pdf"))
        assertEquals("Hej!", quoteText(null, "Hej!"))
    }

    @Test
    fun `each phone shows a quote's media label in its own language`() {
        assertEquals(UiText.res(R.string.chat_media_photo), quoteUiText("📷 Photo"))
        assertEquals(
            UiText.res(R.string.chat_media_with_caption, UiText.res(R.string.chat_media_voice), "Szia"),
            quoteUiText("🎤 Voice message: Szia"),
        )
        for (kind in MediaKind.entries) {
            assertEquals(UiText.res(kind.label), quoteUiText(quoteText(kind, "")))
        }
    }

    @Test
    fun `text and named files are quoted as they are`() {
        assertEquals(UiText.Plain("Hej!"), quoteUiText("Hej!"))
        assertEquals(UiText.Plain("📎 menu.pdf"), quoteUiText("📎 menu.pdf"))
        assertEquals(UiText.Plain("📷 Photographs are nice"), quoteUiText("📷 Photographs are nice"))
    }
}
