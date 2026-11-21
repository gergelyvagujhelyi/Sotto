package com.sotto.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.sotto.app.domain.model.ReactionPayload
import com.sotto.app.ui.theme.Aurora

/**
 * The quick reactions over a held message, as on WhatsApp: six emoji and "+" for more.
 * The user's current reaction is ringed; picking it again takes it back. Shown above
 * the message, or below it when the message is at the top of the screen.
 */
@Composable
internal fun ReactionPicker(
    current: String?,
    alignEnd: Boolean,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    Popup(
        popupPositionProvider = AboveOrBelow(alignEnd, gap),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
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
                    .clickable(onClickLabel = "More reactions", role = Role.Button, onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "More reactions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun EmojiChoice(emoji: String, selected: Boolean, onClick: () -> Unit, size: Int = 40) {
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
                contentDescription = if (selected) "$emoji, your reaction. Tap to take it back" else "React $emoji"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size * 0.6f).sp)
    }
}

/** All the reactions behind "+", in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreReactionsSheet(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(ReactionPayload.MORE.distinct()) { emoji ->
                Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
                    EmojiChoice(emoji, selected = emoji == current, onClick = { onPick(emoji) }, size = 44)
                }
            }
        }
    }
}

/** Above the anchor, lined up with its [alignEnd] side; below it when there's no room above. */
private class AboveOrBelow(private val alignEnd: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val end = alignEnd != (layoutDirection == LayoutDirection.Rtl)
        val x = if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= 0) above else anchorBounds.bottom + gap
        return IntOffset(
            x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)),
        )
    }
}
