package com.sotto.app.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.ReplyPayload
import com.sotto.app.ui.components.BubbleQuote
import com.sotto.app.ui.theme.Aurora
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** How far a bubble is dragged before letting go replies to it. */
private val ReplyThreshold = 64.dp

/** How far it follows the finger at most. */
private val ReplyMaxDrag = 96.dp

/** Messages that can be replied to: readable ones (not the "could not decrypt" placeholders). */
internal val Message.canReply: Boolean
    get() = !state.isCallEvent && !state.isKeyChangeEvent &&
        state != MessageState.REJECTED && state != MessageState.DECRYPT_FAILED &&
        plaintext.isNotEmpty()

/**
 * Drag [content] towards the end (right, or left in right-to-left languages) to reply,
 * as in WhatsApp: a reply arrow fades in behind it, the phone ticks once it's far enough,
 * and letting go there calls [onReply]. It springs back either way. Screen readers get
 * "Reply" as an action instead.
 */
@Composable
internal fun SwipeToReply(
    enabled: Boolean,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val threshold = with(density) { ReplyThreshold.toPx() }
    val max = with(density) { ReplyMaxDrag.toPx() }
    val reply by rememberUpdatedState(onReply)
    // How far it's dragged towards the end, in px (always >= 0).
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .semantics {
                customActions = listOf(CustomAccessibilityAction("Reply") { reply(); true })
            }
            .pointerInput(rtl) {
                fun settle() {
                    val from = drag
                    scope.launch {
                        animate(from, 0f, animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) { v, _ ->
                            drag = v
                        }
                    }
                }
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag >= threshold) reply()
                        settle()
                    },
                    onDragCancel = { settle() },
                ) { change, dx ->
                    val towardsEnd = if (rtl) -dx else dx
                    // Past the threshold it gets heavier, so it's clear that's far enough.
                    val step = if (drag >= threshold) towardsEnd * 0.35f else towardsEnd
                    val next = (drag + step).coerceIn(0f, max)
                    if (drag < threshold && next >= threshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag = next
                    change.consume()
                }
            },
    ) {
        val progress = (drag / threshold).coerceIn(0f, 1f)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .size(34.dp)
                .graphicsLayer {
                    alpha = progress
                    val scale = 0.6f + 0.4f * progress
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(Modifier.offset { IntOffset(drag.roundToInt() * if (rtl) -1 else 1, 0) }) { content() }
    }
}

/** A brief tint over the message a quote was tapped for, so the eye finds it. */
@Composable
internal fun Modifier.replyHighlight(active: Boolean): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (active) 0.22f else 0f,
        animationSpec = tween(if (active) 150 else 900),
        label = "replyHighlight",
    )
    val tint = MaterialTheme.colorScheme.primary
    return if (alpha == 0f) this else background(tint.copy(alpha = alpha), RoundedCornerShape(12.dp))
}

/** What a bubble shows of the message this one replies to: "You" or [contactName], and its start. */
internal fun Message.bubbleQuote(contactName: String): BubbleQuote? =
    replyTo?.let { BubbleQuote(author = if (it.toOwn) "You" else contactName, text = it.quote, toOwn = it.toOwn) }

/**
 * Above the composer while replying: who wrote [message] and its first line, with a
 * button to stop replying. Same look as the quote in the bubble.
 */
@Composable
internal fun ReplyPreview(message: Message, contactName: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = if (message.isOutgoing) colors.primary else colors.tertiary
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(if (Aurora.colors.isDark) colors.surfaceContainerHigh else colors.surfaceContainerLowest)
            .background(accent.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = if (message.isOutgoing) "You" else contactName,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ReplyPayload.quoteOf(message.plaintext),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel reply", tint = colors.onSurfaceVariant)
        }
    }
}
