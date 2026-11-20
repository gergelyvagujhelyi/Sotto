package com.sotto.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.sotto.app.domain.model.MessageState
import com.sotto.app.ui.theme.Aurora
import com.sotto.app.ui.theme.LocalReduceMotion
import kotlin.math.ceil
import kotlinx.coroutines.launch

/**
 * A chat bubble. Sent messages are filled with the brand gradient; received ones
 * are glass. Consecutive messages from one side form a group: [groupedWithPrevious]
 * tightens the corner that touches the bubble above. The time (and, for sent
 * messages, the delivery state) tucks into the end of the last line when it fits.
 */
@Composable
fun MessageBubble(
    text: String,
    timestamp: String,
    isOutgoing: Boolean,
    state: MessageState,
    modifier: Modifier = Modifier,
    groupedWithPrevious: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** New while the chat is open: rise into place while the text settles like a departure board. */
    animateIn: Boolean = false,
    onAnimateInDone: () -> Unit = {},
    /** A copy is flying in from the composer to land here: keep the space, show nothing yet. */
    hidden: Boolean = false,
    /** Where the bubble itself is on screen (root coordinates), for that copy to land on. */
    onBubblePlaced: ((Rect) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // [rise] goes from 1 (low and small, as if coming up from below) to 0 (in place);
    // [scrambleMs] runs the departure board, null when not flipping.
    val animate = animateIn && !LocalReduceMotion.current
    val rise = remember { Animatable(if (animate) 1f else 0f) }
    var scrambleMs by remember { mutableStateOf(if (animate) 0L else null) }
    LaunchedEffect(animate) {
        if (!animate) {
            scrambleMs = null
            rise.snapTo(0f)
            if (animateIn) onAnimateInDone()
            return@LaunchedEffect
        }
        launch { rise.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
        runScramble(text.length) { scrambleMs = it }
        scrambleMs = null
        onAnimateInDone()
    }
    val failed = isOutgoing && state == MessageState.FAILED

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
    ) {
        BubbleSurface(
            text = text,
            timestamp = timestamp,
            isOutgoing = isOutgoing,
            state = state,
            groupedWithPrevious = groupedWithPrevious,
            onClick = onClick,
            scrambleMs = scrambleMs,
            modifier = Modifier
                .then(if (onBubblePlaced != null) Modifier.onGloballyPositioned { onBubblePlaced(it.boundsInRoot()) } else Modifier)
                .graphicsLayer {
                    if (hidden) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val p = rise.value
                    if (p != 0f) {
                        translationY = p * 120.dp.toPx()
                        scaleX = 1f - 0.12f * p
                        scaleY = 1f - 0.12f * p
                        alpha = (1f - 0.7f * p).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(if (isOutgoing) 1f else 0f, 1f)
                    }
                },
        )
        if (failed) {
            // The label says "tap to retry", so it retries too, not just the bubble.
            Text(
                text = "Not delivered · Tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Runs the departure board for a text of [length]: [onFrame] gets the ms since it started, each frame until it settles. */
internal suspend fun runScramble(length: Int, onFrame: (Long) -> Unit) {
    val total = Scramble.duration(length)
    val start = withFrameMillis { it }
    while (true) {
        val elapsed = withFrameMillis { it } - start
        if (elapsed >= total) break
        onFrame(elapsed)
    }
}

/** How far a bubble's text sits from its top-left corner. */
internal val BubbleTextInsetStart = 14.dp
internal val BubbleTextInsetTop = 9.dp

/**
 * The bubble itself: its shape, fill, text, and the time with the delivery state.
 * [look] blends it in for the copy that flies out of the composer: at 0 it's the
 * composer's plain text (no fill, no time), at 1 a finished bubble.
 */
@Composable
internal fun BubbleSurface(
    text: String,
    timestamp: String,
    isOutgoing: Boolean,
    state: MessageState,
    groupedWithPrevious: Boolean,
    onClick: (() -> Unit)?,
    scrambleMs: Long?,
    modifier: Modifier = Modifier,
    look: Float = 1f,
) {
    val colors = MaterialTheme.colorScheme
    val aurora = Aurora.colors
    val failed = isOutgoing && state == MessageState.FAILED
    val unreadable = state == MessageState.REJECTED || state == MessageState.DECRYPT_FAILED
    val fill = look.coerceIn(0f, 1f)

    val round = 20.dp
    val tight = 6.dp
    val shape = if (isOutgoing) {
        RoundedCornerShape(topStart = round, topEnd = if (groupedWithPrevious) tight else round, bottomEnd = tight, bottomStart = round)
    } else {
        RoundedCornerShape(topStart = if (groupedWithPrevious) tight else round, topEnd = round, bottomEnd = round, bottomStart = tight)
    }
    val bubbleTextColor = when {
        failed -> colors.onErrorContainer
        isOutgoing -> aurora.onBubbleOutgoing
        unreadable -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    val textColor = if (fill >= 1f) bubbleTextColor else lerp(colors.onSurface, bubbleTextColor, fill)
    val metaColor = when {
        failed -> colors.onErrorContainer.copy(alpha = 0.7f)
        isOutgoing -> aurora.bubbleOutgoingMeta
        else -> colors.onSurfaceVariant
    }
    val maxWidth = min((LocalConfiguration.current.screenWidthDp * 0.78f).dp, 420.dp)
    val surface = when {
        failed -> Modifier.background(colors.errorContainer.copy(alpha = colors.errorContainer.alpha * fill), shape)
        isOutgoing -> Modifier
            .background(Brush.linearGradient(aurora.bubbleOutgoing), shape, alpha = fill)
            .border(1.dp, aurora.bubbleOutgoingBorder.copy(alpha = aurora.bubbleOutgoingBorder.alpha * fill), shape)
        else -> Modifier
            .background(aurora.bubbleIncoming.copy(alpha = aurora.bubbleIncoming.alpha * fill), shape)
            .border(1.dp, aurora.bubbleIncomingBorder.copy(alpha = aurora.bubbleIncomingBorder.alpha * fill), shape)
    }
    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .then(modifier)
            .then(surface)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = BubbleTextInsetStart, end = 12.dp, top = BubbleTextInsetTop, bottom = 8.dp),
    ) {
        TextWithTrailingMeta(
            text = text,
            style = if (unreadable) {
                MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = textColor,
            scrambleMs = scrambleMs,
        ) {
            Row(
                // The time and ticks come in at the end of a flight.
                modifier = if (fill >= 1f) Modifier else Modifier.graphicsLayer { alpha = ((fill - 0.6f) / 0.4f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = timestamp, style = MaterialTheme.typography.labelSmall, color = metaColor)
                // Ticks sit dimmer than the time so the bright read tick stands out.
                if (isOutgoing) MessageStateIcon(state, if (failed) metaColor else aurora.bubbleOutgoingTick)
            }
        }
    }
}

/**
 * Lays [meta] at the end of the text's last line when there's room, otherwise on
 * its own line below — the compact layout of modern messengers.
 */
@Composable
private fun TextWithTrailingMeta(
    text: String,
    style: TextStyle,
    color: Color,
    /** Departure board running, ms since it started; null for plain text. */
    scrambleMs: Long? = null,
    meta: @Composable () -> Unit,
) {
    // Written during the text's own measure pass and read right after it in the
    // same pass, so this must not be snapshot state.
    val textLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    // While flipping, the real text keeps the layout (the bubble never changes size) with
    // the unsettled characters transparent; their glyphs are drawn on top, in each one's box.
    val shown = if (scrambleMs == null) AnnotatedString(text) else unsettledHidden(text, scrambleMs)
    val measurer = rememberTextMeasurer()
    val glyphLayouts = remember(style) { HashMap<Char, TextLayoutResult>() }
    val glyphColor = color.copy(alpha = 0.9f)
    Layout(
        content = {
            Text(
                text = shown,
                style = style,
                color = color,
                onTextLayout = { textLayout[0] = it },
                modifier = if (scrambleMs == null) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        drawContent()
                        val layout = textLayout[0] ?: return@drawWithContent
                        for (i in text.indices) {
                            val ch = text[i]
                            if (ch.isWhitespace() || Character.isLowSurrogate(ch)) continue
                            if (scrambleMs >= Scramble.settleAt(i, text.length)) continue
                            if (i >= layout.layoutInput.text.length) break
                            val box = layout.getBoundingBox(i)
                            val glyph = Scramble.glyphAt(ch, i, scrambleMs)
                            val g = glyphLayouts.getOrPut(glyph) { measurer.measure(glyph.toString(), style) }
                            drawText(
                                textLayoutResult = g,
                                color = glyphColor,
                                topLeft = Offset(
                                    box.left + (box.width - g.size.width) / 2f,
                                    box.top + (box.height - g.size.height) / 2f,
                                ),
                            )
                        }
                    }
                },
            )
            meta()
        },
    ) { measurables, constraints ->
        val textPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val metaPlaceable = measurables[1].measure(Constraints())
        val gap = 8.dp.roundToPx()
        val lastLineRight = textLayout[0]
            ?.takeIf { it.lineCount > 0 }
            ?.let { ceil(it.getLineRight(it.lineCount - 1)).toInt() }
            ?: textPlaceable.width
        val inlineWidth = lastLineRight + gap + metaPlaceable.width
        val inline = inlineWidth <= constraints.maxWidth
        val width = if (inline) {
            maxOf(textPlaceable.width, inlineWidth)
        } else {
            maxOf(textPlaceable.width, metaPlaceable.width)
        }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = if (inline) {
            maxOf(textPlaceable.height, metaPlaceable.height)
        } else {
            textPlaceable.height + metaPlaceable.height
        }
        layout(width, height) {
            textPlaceable.placeRelative(0, 0)
            metaPlaceable.placeRelative(width - metaPlaceable.width, height - metaPlaceable.height)
        }
    }
}

@Composable
private fun MessageStateIcon(state: MessageState, baseTint: Color) {
    val (icon, tint) = when (state) {
        MessageState.SENDING -> Icons.Rounded.Schedule to baseTint
        MessageState.SENT -> Icons.Rounded.Done to baseTint
        MessageState.DELIVERED -> Icons.Rounded.DoneAll to baseTint
        MessageState.SEEN -> Icons.Rounded.DoneAll to Aurora.colors.readTick
        MessageState.FAILED -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        MessageState.DECRYPT_FAILED -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        MessageState.REJECTED -> Icons.Rounded.Block to MaterialTheme.colorScheme.error
        // Call/key events are rendered by their own composables — but keep the when exhaustive
        MessageState.CALL_OUTGOING, MessageState.CALL_OUTGOING_MISSED, MessageState.CALL_INCOMING, MessageState.CALL_MISSED,
        MessageState.KEY_ROTATED_LOCAL, MessageState.KEY_ROTATED_REMOTE, MessageState.ACCOUNT_DELETED ->
            Icons.Rounded.Done to baseTint
    }
    Icon(
        imageVector = icon,
        contentDescription = state.name.lowercase(),
        tint = tint,
        modifier = Modifier.size(15.dp),
    )
}


/** [text] with the characters that haven't settled yet (at [elapsedMs]) transparent. */
private fun unsettledHidden(text: String, elapsedMs: Long): AnnotatedString {
    val hidden = SpanStyle(color = Color.Transparent)
    return buildAnnotatedString {
        append(text)
        var start = -1
        for (i in text.indices) {
            // A low surrogate goes with the character before it.
            val owner = if (Character.isLowSurrogate(text[i]) && i > 0) i - 1 else i
            val unsettled = elapsedMs < Scramble.settleAt(owner, text.length)
            if (unsettled && start < 0) start = i
            if (!unsettled && start >= 0) {
                addStyle(hidden, start, i)
                start = -1
            }
        }
        if (start >= 0) addStyle(hidden, start, text.length)
    }
}
