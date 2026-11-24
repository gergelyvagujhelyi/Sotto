package com.sotto.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sotto.app.R
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today's date, kept current: refreshed whenever the screen resumes and every
 * minute while it is shown, so "Today", "Yesterday" and same-day times roll over
 * at midnight instead of going stale on a screen left open.
 */
@Composable
fun rememberToday(): LocalDate {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val today by produceState(Clock.System.todayIn(TimeZone.currentSystemDefault()), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = Clock.System.todayIn(TimeZone.currentSystemDefault())
                delay(60_000)
            }
        }
    }
    return today
}

/** HH:mm, 24-hour. */
internal fun LocalTime.clockTime(): String = "%02d:%02d".format(hour, minute)

/**
 * How dates are written in the app's language: the words for today and yesterday, and
 * java.time patterns whose month and day names come from [locale]. The patterns and the
 * locale are strings of the translation (strings_chat.xml), so a phone in a language Sotto
 * isn't translated to keeps the English dates along with the English text.
 */
class DateFormats(
    val today: String,
    val yesterday: String,
    locale: Locale,
    weekday: String,
    dayThisYear: String,
    dayOtherYear: String,
    listThisYear: String,
    listOtherYear: String,
) {
    internal val weekday = formatter(weekday, "EEEE", locale)
    internal val dayThisYear = formatter(dayThisYear, "EEE, d MMM", locale)
    internal val dayOtherYear = formatter(dayOtherYear, "d MMM yyyy", locale)
    internal val listThisYear = formatter(listThisYear, "dd MMM", locale)
    internal val listOtherYear = formatter(listOtherYear, "dd/MM/yyyy", locale)

    companion object {
        /** A translation's broken pattern falls back to the English one instead of crashing the chat screens. */
        private fun formatter(pattern: String, english: String, locale: Locale): DateTimeFormatter =
            runCatching { DateTimeFormatter.ofPattern(pattern, locale) }
                .getOrElse { DateTimeFormatter.ofPattern(english, Locale.ENGLISH) }

        fun from(context: Context): DateFormats = DateFormats(
            today = context.getString(R.string.chat_date_today),
            yesterday = context.getString(R.string.chat_date_yesterday),
            locale = appLanguage(context),
            weekday = context.getString(R.string.chat_date_weekday),
            dayThisYear = context.getString(R.string.chat_date_this_year),
            dayOtherYear = context.getString(R.string.chat_date_other_year),
            listThisYear = context.getString(R.string.conversations_date_this_year),
            listOtherYear = context.getString(R.string.conversations_date_other_year),
        )
    }
}

/**
 * The language of the translation shown ([R.string.chat_date_locale]), for month and day names.
 * Not the phone's: on a phone in a language Sotto doesn't have, its text is English and so are its dates.
 */
fun appLanguage(context: Context): Locale = Locale.forLanguageTag(context.getString(R.string.chat_date_locale))

/** [DateFormats] in the app's language, made again when the language changes. */
@Composable
fun rememberDateFormats(): DateFormats {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { DateFormats.from(context) }
}

/** Day separator in a conversation: "Today", "Yesterday", a weekday, then "Mon, 3 Nov" or "3 Nov 2025". */
internal fun dayLabel(date: LocalDate, today: LocalDate, formats: DateFormats): String {
    val daysAgo = today.toEpochDays() - date.toEpochDays()
    val day = date.toJavaLocalDate()
    return when {
        daysAgo == 0 -> formats.today
        daysAgo == 1 -> formats.yesterday
        daysAgo in 2..6 -> formats.weekday.format(day)
        date.year == today.year -> formats.dayThisYear.format(day)
        else -> formats.dayOtherYear.format(day)
    }
}

/** Time of a conversation's last message: HH:mm today, "03 Nov" this year, "03/11/2025" before. */
internal fun listTimestamp(instant: Instant, today: LocalDate, formats: DateFormats): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return when {
        local.date == today -> local.time.clockTime()
        local.year == today.year -> formats.listThisYear.format(local.date.toJavaLocalDate())
        else -> formats.listOtherYear.format(local.date.toJavaLocalDate())
    }
}
