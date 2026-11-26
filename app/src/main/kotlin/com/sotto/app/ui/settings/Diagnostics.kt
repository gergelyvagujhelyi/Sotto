package com.sotto.app.ui.settings

import android.content.Context
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.resolve

/**
 * One line of Settings > Diagnostics: what Sotto knows about how this phone reaches the relay.
 * It stays on the phone; the user shares it (a screenshot or Share) when asking for help.
 */
data class DiagnosticsLine(val label: UiText, val value: UiText)

/** Plain text for Share: a title, then "label: value" per line. */
fun List<DiagnosticsLine>.shareText(context: Context, title: String): String =
    buildString {
        appendLine(title)
        for (line in this@shareText) appendLine("${line.label.resolve(context)}: ${line.value.resolve(context)}")
    }.trimEnd()
