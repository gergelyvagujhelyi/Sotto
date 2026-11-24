package com.sotto.app.ui.components

import com.sotto.app.testing.English
import com.sotto.app.testing.Hungarian
import com.sotto.app.testing.StringsXml
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** With the patterns the strings files really have, so a broken translation fails here. */
class DateLabelsTest {
    private val english = formats(English)
    private val hungarian = formats(Hungarian)

    private fun formats(strings: StringsXml) = DateFormats(
        today = strings["chat_date_today"], yesterday = strings["chat_date_yesterday"],
        locale = Locale.forLanguageTag(strings["chat_date_locale"]),
        weekday = strings["chat_date_weekday"], dayThisYear = strings["chat_date_this_year"],
        dayOtherYear = strings["chat_date_other_year"], listThisYear = strings["conversations_date_this_year"],
        listOtherYear = strings["conversations_date_other_year"],
    )

    @Test
    fun `a broken pattern falls back to English instead of crashing`() {
        val broken = DateFormats(
            today = "Ma", yesterday = "Tegnap", locale = Locale.forLanguageTag("hu"),
            weekday = "EEEE", dayThisYear = "{{nope", dayOtherYear = "yyyy. MMM d.",
            listThisYear = "MMM d.", listOtherYear = "yyyy. MM. dd.",
        )
        assertEquals("Mon, 3 Nov", dayLabel(LocalDate(2025, 11, 3), today, broken))
    }

    private val today = LocalDate(2025, 11, 10) // a Monday

    @Test
    fun `day separators in English`() {
        assertEquals("Today", dayLabel(today, today, english))
        assertEquals("Yesterday", dayLabel(LocalDate(2025, 11, 9), today, english))
        assertEquals("Wednesday", dayLabel(LocalDate(2025, 11, 5), today, english))
        assertEquals("Mon, 3 Nov", dayLabel(LocalDate(2025, 11, 3), today, english))
        assertEquals("Tue, 2 Sep", dayLabel(LocalDate(2025, 9, 2), today, english))
        assertEquals("4 Nov 2024", dayLabel(LocalDate(2024, 11, 4), today, english))
    }

    @Test
    fun `day separators in Hungarian`() {
        assertEquals("Ma", dayLabel(today, today, hungarian))
        assertEquals("Tegnap", dayLabel(LocalDate(2025, 11, 9), today, hungarian))
        assertEquals("szerda", dayLabel(LocalDate(2025, 11, 5), today, hungarian))
        assertEquals("nov. 3., hétfő", dayLabel(LocalDate(2025, 11, 3), today, hungarian))
        assertEquals("2024. nov. 4.", dayLabel(LocalDate(2024, 11, 4), today, hungarian))
    }

    @Test
    fun `chat list times`() {
        fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 5) =
            LocalDateTime(y, m, d, h, min).toInstant(TimeZone.currentSystemDefault())
        assertEquals("09:05", listTimestamp(at(2025, 11, 10), today, english))
        assertEquals("03 Nov", listTimestamp(at(2025, 11, 3), today, english))
        assertEquals("03/11/2024", listTimestamp(at(2024, 11, 3), today, english))
        assertEquals("09:05", listTimestamp(at(2025, 11, 10), today, hungarian))
        assertEquals("nov. 3.", listTimestamp(at(2025, 11, 3), today, hungarian))
        assertEquals("2024. 11. 03.", listTimestamp(at(2024, 11, 3), today, hungarian))
    }
}
