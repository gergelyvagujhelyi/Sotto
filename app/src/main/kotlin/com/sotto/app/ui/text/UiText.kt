package com.sotto.app.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Text for the user, decided where there is no Context (view models, pure functions) and
 * turned into words in the user's language where it is shown. Equal when the resource and
 * its arguments are, so unit tests compare these instead of English sentences.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    /** Text that isn't ours to translate: a name, a message, an address. */
    data class Plain(val text: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

fun UiText.resolve(context: Context): String = when (this) {
    // Without arguments the text isn't a format: a literal % in it must not be read as one.
    is UiText.Res -> if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.map { it.resolveArg(context) }.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.map { it.resolveArg(context) }.toTypedArray())
    is UiText.Plain -> text
}

/** An argument may itself be a [UiText]: "%1$s ago" with a translated unit. */
private fun Any.resolveArg(context: Context): Any = if (this is UiText) resolve(context) else this

/** Read again when the configuration (the language) changes, like stringResource. */
@Composable
fun UiText.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
