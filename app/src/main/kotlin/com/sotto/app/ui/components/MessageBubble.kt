package com.sotto.app.ui.components

import androidx.compose.ui.res.stringResource
import com.sotto.app.R
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.sotto.app.domain.model.MessageState
import com.sotto.app.ui.theme.Aurora
import com.sotto.app.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
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
    /** The message this one replies to, shown above its text. */
    quote: BubbleQuote? = null,
    /** Tapping the quote: go to the original. */
    onQuoteClick: (() -> Unit)? = null,
    /** New while the chat is open: rise into place while the text settles like a departure board. */
    animateIn: Boolean = false,
    onAnimateInDone: () -> Unit = {},
    /** A copy is flying in from the composer to land here: keep the space, show nothing yet. */
    hidden: Boolean = false,
    /** Where the bubble itself is on screen (root coordinates), for that copy to land on. */
    onBubblePlaced: ((Rect) -> Unit)? = null,
    /** Holding the bubble: react to it. */
    onLongClick: (() -> Unit)? = null,
    /** Double-tapping the bubble: heart it (or take the heart back). */
    onDoubleClick: (() -> Unit)? = null,
    /** The emoji reactions on it, shown under its corner; null when none. */
    reactions: BubbleReactions? = null,
    /** Tapping the reactions: change or take back the user's. */
    onReactionsClick: (() -> Unit)? = null,
    /** Shown above the bubble while it's held, e.g. the reaction picker. */
    overlay: (@Composable () -> Unit)? = null,
    /** A voice message or photo, shown above the text (its caption); gets the bubble's text colour. */
    media: (@Composable (Color) -> Unit)? = null,
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
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
            scrambleMs = scrambleMs,
            quote = quote,
            onQuoteClick = onQuoteClick,
            media = media,
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
        overlay?.invoke()
        if (reactions != null && !reactions.isEmpty && !hidden) {
            ReactionChip(
                reactions = reactions,
                onClick = onReactionsClick,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    // Tucked over the bubble's bottom edge, as on WhatsApp.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val overlap = ReactionOverlap.roundToPx()
                        layout(placeable.width, placeable.height - overlap) { placeable.place(0, -overlap) }
                    },
            )
        }
        if (failed) {
            // The label says "tap to retry", so it retries too, not just the bubble.
            Text(
                text = stringResource(R.string.chat_not_delivered),
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
@OptIn(ExperimentalFoundationApi::class)
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
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    look: Float = 1f,
    quote: BubbleQuote? = null,
    onQuoteClick: (() -> Unit)? = null,
    media: (@Composable (Color) -> Unit)? = null,
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
            .then(
                when {
                    onLongClick != null || onDoubleClick != null -> Modifier.combinedClickable(
                        onClickLabel = null,
                        onLongClickLabel = if (onLongClick != null) stringResource(R.string.chat_react) else null,
                        onLongClick = onLongClick,
                        onDoubleClick = onDoubleClick,
                        onClick = onClick ?: {},
                    )
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .then(
                // A quote sits close to the bubble's edge; the text keeps its usual inset.
                if (quote == null) {
                    Modifier.padding(start = BubbleTextInsetStart, end = 12.dp, top = BubbleTextInsetTop, bottom = 8.dp)
                } else {
                    Modifier.padding(start = QuoteInset, end = QuoteInset, top = QuoteInset, bottom = 8.dp)
                },
            ),
    ) {
        QuoteAbove(
            quote = quote?.let { q ->
                {
                    QuoteBlock(
                        quote = q,
                        isOutgoing = isOutgoing,
                        textColor = bubbleTextColor,
                        onClick = onQuoteClick,
                    )
                }
            },
        ) {
            // Media alone (no caption): its time and ticks sit at the bottom end, as on WhatsApp.
            Column(horizontalAlignment = if (media != null && text.isEmpty()) Alignment.End else Alignment.Start) {
            if (media != null) {
                // Media that takes its own taps (open a photo, play) passes holding and
                // double-tapping on to the bubble.
                CompositionLocalProvider(LocalBubbleGestures provides BubbleGestures(onLongClick, onDoubleClick)) {
                    media(bubbleTextColor)
                }
            }
            TextWithTrailingMeta(
                text = text,
                style = if (unreadable) {
                    MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = textColor,
                scrambleMs = scrambleMs,
                // Not in the copy flying out of the composer: it's a picture of the text.
                links = !unreadable && look >= 1f,
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
    }
}

/** What holding and double-tapping a bubble do, for media in it that handles its own taps. */
class BubbleGestures(val onLongClick: (() -> Unit)?, val onDoubleClick: (() -> Unit)?)

val LocalBubbleGestures = compositionLocalOf { BubbleGestures(null, null) }

/**
 * The reactions on a bubble: the contact's emoji and the user's, each null when they gave none.
 * [theirName] null: "Them".
 */
data class BubbleReactions(val theirs: String?, val mine: String?, val theirName: String? = null) {
    val isEmpty: Boolean get() = theirs == null && mine == null
}

/** How far the reaction chip reaches up over the bubble. */
private val ReactionOverlap = 6.dp

/** The reactions under a bubble: one emoji each, or one emoji and "2" when both gave the same. */
@Composable
private fun ReactionChip(reactions: BubbleReactions, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val label = when {
        reactions.theirs != null && reactions.theirs == reactions.mine -> "${reactions.mine} 2"
        else -> listOfNotNull(reactions.theirs, reactions.mine).joinToString(" ")
    }
    val theirName = reactions.theirName ?: stringResource(R.string.chat_them)
    val description = listOfNotNull(
        reactions.theirs?.let { stringResource(R.string.chat_reaction_theirs_description, theirName, it) },
        reactions.mine?.let { stringResource(R.string.chat_reaction_mine_description, it) },
    ).joinToString(", ")
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurface,
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerHigh, shape)
            .border(1.dp, Aurora.colors.glassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.chat_reaction_change), onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** What a reply bubble shows of the message it answers. [author] is "You" or the contact's name. */
data class BubbleQuote(val author: String, val text: String, val toOwn: Boolean)

/** How far a quote sits from the bubble's edge. */
private val QuoteInset = 5.dp

/**
 * [body] (the text and time) with [quote] above it, when there is one. The bubble is as
 * wide as the wider of the two (up to its limit), and the quote spans all of it.
 */
@Composable
private fun QuoteAbove(quote: (@Composable () -> Unit)?, body: @Composable () -> Unit) {
    if (quote == null) {
        body()
        return
    }
    Layout(
        content = {
            quote()
            // The text keeps its usual inset from the bubble's edge.
            Box(
                Modifier.padding(
                    start = BubbleTextInsetStart - QuoteInset,
                    end = 12.dp - QuoteInset,
                    top = 6.dp,
                ),
                propagateMinConstraints = true,
            ) { body() }
        },
    ) { measurables, constraints ->
        val wanted = measurables[0].maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(constraints.maxWidth)
        val bodyPlaceable = measurables[1].measure(
            constraints.copy(minWidth = maxOf(constraints.minWidth, wanted).coerceAtMost(constraints.maxWidth)),
        )
        val quotePlaceable = measurables[0].measure(Constraints.fixedWidth(bodyPlaceable.width))
        layout(bodyPlaceable.width, quotePlaceable.height + bodyPlaceable.height) {
            quotePlaceable.placeRelative(0, 0)
            bodyPlaceable.placeRelative(0, quotePlaceable.height)
        }
    }
}

/**
 * The quoted message inside a reply: a bar in its author's colour, their name and two
 * lines of the text, over a faint tint of the bubble's text colour (so it reads on the
 * brand gradient and on glass, light or dark).
 */
@Composable
private fun QuoteBlock(quote: BubbleQuote, isOutgoing: Boolean, textColor: Color, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    // On the brand gradient, colours fight the fill: the bubble's own text colour reads best.
    val accent = when {
        isOutgoing -> textColor
        quote.toOwn -> colors.primary
        else -> colors.tertiary
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(textColor.copy(alpha = 0.1f))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.chat_quote_go_to), onClick = onClick) else Modifier),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent.copy(alpha = if (isOutgoing) 0.75f else 1f)),
        )
        Column(Modifier.weight(1f, fill = false).padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 7.dp)) {
            Text(
                text = quote.author,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = quote.text.ifBlank { stringResource(R.string.chat_quote_fallback) },
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
    /** Web links in the text are underlined and open on tap (once the board has settled). */
    links: Boolean = false,
    meta: @Composable () -> Unit,
) {
    // Written during the text's own measure pass and read right after it in the
    // same pass, so this must not be snapshot state.
    val textLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    // While flipping, the real text keeps the layout (the bubble never changes size) with
    // the unsettled characters transparent; their glyphs are drawn on top, in each one's box.
    val linked = rememberLinked(text, color, links)
    val shown = if (scrambleMs == null) linked else unsettledHidden(text, scrambleMs)
    val measurer = rememberTextMeasurer()
    val glyphLayouts = remember(style) { HashMap<String, TextLayoutResult>() }
    val layoutOf = { glyph: String -> glyphLayouts.getOrPut(glyph) { measurer.measure(glyph, style) } }
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
                        // Capitals are about 0.7 of the font size.
                        val halfCap = 0.35f * (if (style.fontSize.isSp) style.fontSize else 16.sp).toPx()
                        for (i in text.indices) {
                            val ch = text[i]
                            if (ch.isWhitespace() || Character.isLowSurrogate(ch)) continue
                            if (i >= layout.layoutInput.text.length) break
                            val real = if (Character.isHighSurrogate(ch) && i + 1 < text.length) {
                                text.substring(i, i + 2)
                            } else {
                                ch.toString()
                            }
                            val flap = Scramble.flapAt(real, i, text.length, scrambleMs) ?: continue
                            drawFlap(flap, layout.getBoundingBox(i), layoutOf, color, halfCap)
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
        MessageState.CALL_FAILED,
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


/**
 * [text] with its web links ([findLinks]) underlined and opening on tap. A link's
 * tap goes to the link, not to the bubble. With nothing to open it, a toast says so
 * instead of the app crashing.
 */
@Composable
private fun rememberLinked(text: String, color: Color, enabled: Boolean): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    return remember(text, color, enabled, uriHandler) {
        val found = if (enabled) findLinks(text) else emptyList()
        if (found.isEmpty()) return@remember AnnotatedString(text)
        val styles = TextLinkStyles(
            style = SpanStyle(textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline, background = color.copy(alpha = 0.2f)),
        )
        buildAnnotatedString {
            append(text)
            for (link in found) {
                addLink(
                    LinkAnnotation.Url(link.url, styles) {
                        try {
                            uriHandler.openUri(link.url)
                        } catch (_: IllegalArgumentException) {
                            // No browser (or other app) for it.
                            Toast.makeText(context, context.getString(R.string.chat_no_app_for_link), Toast.LENGTH_SHORT).show()
                        }
                    },
                    link.range.first,
                    link.range.last + 1,
                )
            }
        }
    }
}

/**
 * One split-flap flip in [box], the character's place in the text. The top half
 * already shows [Scramble.Flap.to] and the bottom half still [Scramble.Flap.from],
 * while the flap folds over the middle line: first `from`'s top half falls until
 * it is edge-on, then `to`'s bottom half comes down over `from`'s.
 */
private fun DrawScope.drawFlap(
    flap: Scramble.Flap,
    box: Rect,
    layoutOf: (String) -> TextLayoutResult,
    color: Color,
    /** Half the capitals' height: the flaps fold at their middle, as a board's do. */
    halfCap: Float,
) {
    val from = layoutOf(flap.from)
    val to = layoutOf(flap.to)
    val cx = box.center.x
    val cy = box.center.y
    val mid = cy - to.size.height / 2f + to.firstBaseline - halfCap
    val left = box.left - 1f
    val right = box.right + 1f
    val top = minOf(box.top, cy - to.size.height / 2f, cy - from.size.height / 2f) - 1f
    val bottom = maxOf(box.bottom, cy + to.size.height / 2f, cy + from.size.height / 2f) + 1f

    fun glyph(g: TextLayoutResult, alpha: Float, squash: Float = 1f) {
        // Like a board's tiles, every letter fits its character's width, so a wide one
        // (W standing in for i) doesn't run into its neighbours.
        val fit = if (g.size.width > 0) minOf(1f, box.width / g.size.width) else 1f
        scale(scaleX = fit, scaleY = squash, pivot = Offset(cx, mid)) {
            drawText(
                textLayoutResult = g,
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(cx - g.size.width / 2f, cy - g.size.height / 2f),
            )
        }
    }

    // How far the moving flap reaches from the fold, 1 = a whole half.
    val p = flap.progress
    val falling = p < 0.5f
    val reach = abs(cos(p * PI)).toFloat()
    // A flap seen edge-on catches less light.
    val shade = 0.55f + 0.4f * reach

    // The still halves, each only where the moving flap doesn't cover it.
    clipRect(left, top, right, if (falling) mid - reach * (mid - top) else mid) { glyph(to, 0.9f) }
    clipRect(left, if (falling) mid else mid + reach * (bottom - mid), right, bottom) { glyph(from, 0.9f) }
    // The moving flap, squashed towards the fold.
    if (reach > 0.01f) {
        clipRect(left, if (falling) top else mid, right, if (falling) mid else bottom) {
            glyph(if (falling) from else to, shade, squash = reach)
        }
    }
    // The split between the halves.
    drawLine(
        color = color.copy(alpha = color.alpha * 0.2f),
        start = Offset(box.left + 1f, mid),
        end = Offset(box.right - 1f, mid),
        strokeWidth = 1f,
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
