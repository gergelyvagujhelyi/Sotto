package com.sotto.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sotto.app.ui.theme.Aurora

/**
 * The app's text field: a filled capsule that lights up with the spectrum ring
 * while focused. With an [errorMessage] it keeps a red outline (also while
 * focused) and reports the error to accessibility services.
 */
@Composable
fun AuroraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    errorMessage: String? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    minHeight: Dp = 52.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    trailing: (@Composable () -> Unit)? = null,
    /** Where the typed text starts on screen (root coordinates), whenever that changes. */
    onTextPlaced: ((Offset) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val aurora = Aurora.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    GlowingOutline(
        active = focused && enabled && errorMessage == null,
        shape = shape,
        fill = if (aurora.isDark) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
        idleBorder = if (errorMessage != null) colors.error else aurora.glassBorder,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = minHeight)
                    .semantics { if (errorMessage != null) error(errorMessage) },
                enabled = enabled,
                textStyle = textStyle.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interaction,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else maxLines,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                decorationBox = { innerTextField ->
                    Box(Modifier.padding(contentPadding), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.9f else 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (onTextPlaced != null) {
                            Box(Modifier.onGloballyPositioned { onTextPlaced(it.positionInRoot()) }) { innerTextField() }
                        } else {
                            innerTextField()
                        }
                    }
                },
            )
            trailing?.invoke()
        }
    }
}
