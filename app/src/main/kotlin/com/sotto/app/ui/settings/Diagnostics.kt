package com.sotto.app.ui.settings

import android.content.Context
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.resolve

/**
 * One line of Settings > Diagnostics: what Sotto knows about how this phone reaches the relay.
 * It stays on the phone; the user shares it (a screenshot or Share) when asking for help.
 */
data class DiagnosticsLine(val label: UiText, val value: UiText)

/** Settings > Diagnostics > Test the connection: not run yet, running, or its result lines. */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data class Running(val done: Int, val total: Int) : ConnectionTestState
    data class Done(val lines: List<DiagnosticsLine>) : ConnectionTestState
}

/** The result of one kind of try as a line: "18 of 20 · median 180 ms, slowest 2100 ms". */
fun testLine(label: Int, outcome: com.sotto.app.connection.ConnectionTest.Outcome): DiagnosticsLine =
    DiagnosticsLine(
        UiText.res(label),
        if (outcome.ok == 0) {
            UiText.res(com.sotto.app.R.string.diag_test_none, outcome.total)
        } else {
            UiText.res(com.sotto.app.R.string.diag_test_value, outcome.ok, outcome.total, outcome.median ?: 0L, outcome.slowest ?: 0L)
        },
    )

/** Plain text for Share: a title, then "label: value" per line. */
fun List<DiagnosticsLine>.shareText(context: Context, title: String): String =
    buildString {
        appendLine(title)
        for (line in this@shareText) appendLine("${line.label.resolve(context)}: ${line.value.resolve(context)}")
    }.trimEnd()
