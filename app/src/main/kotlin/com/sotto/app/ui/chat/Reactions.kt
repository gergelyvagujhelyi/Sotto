package com.sotto.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.sotto.app.R
import com.sotto.app.domain.model.ReactionPayload
import com.sotto.app.ui.theme.Aurora

/**
 * The quick reactions over a held message, as on WhatsApp: six emoji and "+" for more,
 * which turns the bar into a grid of every reaction ([expanded]). The user's current
 * reaction is ringed; picking it again takes it back. Shown above the message, or below
 * it when the message is at the top of the screen.
 *
 * Never takes focus: a focused popup (or a bottom sheet) takes it from the text box, the
 * keyboard closes, and the whole chat jumps. So the keyboard stays as it was, and Back is
 * handled by the chat (it closes this). A popup that doesn't take focus lets taps outside
 * it through to the chat, though, so this one covers the whole window: a tap anywhere but
 * the picker only closes it, instead of also opening a link or retrying a message.
 *
 * Under the reactions, for the user's own message, an Unsend button ([onUnsend], ADR-36). A
 * message that can't be reacted to yet (one that failed) gets only that ([showReactions] false).
 */
@Composable
internal fun ReactionPicker(
    current: String?,
    alignEnd: Boolean,
    expanded: Boolean,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    showReactions: Boolean = true,
    onUnsend: (() -> Unit)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    // The popup covers the status bar too; the picker stays below it.
    val statusBar = WindowInsets.statusBars.getTop(density)
    // Where the held message is, in window coordinates (the popup itself fills the window).
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    val wholeWindow = remember { WholeWindow { anchor = it } }
    Popup(
        popupPositionProvider = wholeWindow,
        onDismissRequest = onDismiss,
        // Not clipped: the window starts under the status bar, where the anchor coordinates start too.
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Behind the picker: a tap anywhere else only closes it.
            Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
            val bounds = anchor ?: return@Box
            Layout(content = {
                Column(
                    horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when {
                        expanded -> MoreReactions(current, onPick)
                        showReactions -> QuickReactions(current, onPick, onMore)
                    }
                    if (onUnsend != null && !expanded) UnsendButton(onUnsend)
                }
            }) { measurables, constraints ->
                val window = IntSize(constraints.maxWidth, constraints.maxHeight)
                val picker = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val at = aboveOrBelow(bounds, window, layoutDirection, IntSize(picker.width, picker.height), alignEnd, gap, statusBar)
                layout(window.width, window.height) { picker.place(at) }
            }
        }
    }
}

@Composable
private fun QuickReactions(current: String?, onPick: (String) -> Unit, onMore: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .padding(4.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Aurora.colors.glassBorder, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReactionPayload.QUICK.forEach { emoji ->
            EmojiChoice(emoji, selected = emoji == current, onClick = { onPick(emoji) })
        }
        // A reaction from "+" that isn't a quick one shows ringed in the "+" place.
        val other = current?.takeIf { it !in ReactionPayload.QUICK }
        if (other != null) {
            EmojiChoice(other, selected = true, onClick = { onPick(other) })
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .clickable(onClickLabel = stringResource(R.string.chat_more_reactions), role = Role.Button, onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.chat_more_reactions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** "Unsend", in a pill like the reactions'. */
@Composable
private fun UnsendButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val label = stringResource(R.string.chat_unsend)
    Row(
        modifier = Modifier
            .padding(4.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Aurora.colors.glassBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.Undo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EmojiChoice(emoji: String, selected: Boolean, onClick: () -> Unit, size: Int = 40) {
    val description = if (selected) {
        stringResource(R.string.chat_reaction_yours, emoji)
    } else {
        stringResource(R.string.chat_reaction_pick, emoji)
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size * 0.6f).sp)
    }
}

/** All the reactions behind "+": a scrolling grid in the picker's place. */
@Composable
private fun MoreReactions(current: String?, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(6.dp),
        modifier = Modifier
            .padding(4.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Aurora.colors.glassBorder, shape)
            .size(width = 300.dp, height = 248.dp),
    ) {
        items(ReactionPayload.MORE.distinct()) { emoji ->
            Box(Modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                EmojiChoice(emoji, selected = emoji == current, onClick = { onPick(emoji) }, size = 44)
            }
        }
    }
}

/** Fills the window, and tells where its anchor (the held message) is. */
private class WholeWindow(private val onAnchor: (IntRect) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        onAnchor(anchorBounds)
        return IntOffset.Zero
    }
}

/** Above the anchor, lined up with its [alignEnd] side; below it when there's no room above. */
private fun aboveOrBelow(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
    alignEnd: Boolean,
    gap: Int,
    /** Nothing above this (the status bar). */
    top: Int,
): IntOffset {
    val end = alignEnd != (layoutDirection == LayoutDirection.Rtl)
    val x = if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left
    val above = anchorBounds.top - popupContentSize.height - gap
    val y = if (above >= top) above else anchorBounds.bottom + gap
    return IntOffset(
        x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
        y.coerceIn(top, maxOf(top, windowSize.height - popupContentSize.height)),
    )
}
