package com.sotto.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

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

private fun Month.shortName() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
private fun DayOfWeek.fullName() = name.lowercase().replaceFirstChar { it.uppercase() }
private fun DayOfWeek.shortName() = fullName().take(3)

/** Day separator in a conversation: "Today", "Yesterday", a weekday, then "Mon, 3 Nov" or "3 Nov 2025". */
internal fun dayLabel(date: LocalDate, today: LocalDate): String {
    val daysAgo = today.toEpochDays() - date.toEpochDays()
    return when {
        daysAgo == 0 -> "Today"
        daysAgo == 1 -> "Yesterday"
        daysAgo in 2..6 -> date.dayOfWeek.fullName()
        date.year == today.year -> "${date.dayOfWeek.shortName()}, ${date.dayOfMonth} ${date.month.shortName()}"
        else -> "${date.dayOfMonth} ${date.month.shortName()} ${date.year}"
    }
}

/** Time of a conversation's last message: HH:mm today, "03 Nov" this year, "03/11/2025" before. */
internal fun listTimestamp(instant: Instant, today: LocalDate): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return when {
        local.date == today -> local.time.clockTime()
        local.year == today.year -> "%02d %s".format(local.dayOfMonth, local.month.shortName())
        else -> "%02d/%02d/%d".format(local.dayOfMonth, local.monthNumber, local.year)
    }
}
