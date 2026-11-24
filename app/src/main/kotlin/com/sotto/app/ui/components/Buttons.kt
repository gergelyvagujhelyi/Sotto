package com.sotto.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.ui.theme.Aurora

/**
 * The primary action: a pill filled with the brand gradient, with a coloured glow
 * underneath and a little give when pressed.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    compact: Boolean = false,
    colors: List<Color> = Aurora.colors.brand,
    glow: Color = Aurora.colors.glow,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "pressScale")
    val content = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = if (compact) 42.dp else 56.dp)
            .then(
                if (enabled) {
                    Modifier
                        .shadow(if (compact) 10.dp else 18.dp, CircleShape, ambientColor = glow, spotColor = glow)
                        .background(Brush.horizontalGradient(colors), CircleShape)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape)
                },
            )
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 18.dp else 28.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                color = content,
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

enum class SoftButtonTone { Neutral, Accent, Danger }

/** The quieter companion to [GradientButton]: a tinted glass pill. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: SoftButtonTone = SoftButtonTone.Neutral,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val aurora = Aurora.colors
    val (fill, edge, content) = when (tone) {
        SoftButtonTone.Neutral -> Triple(aurora.glass, aurora.glassBorder, colors.onSurface)
        SoftButtonTone.Accent -> Triple(colors.primary.copy(alpha = 0.12f), colors.primary.copy(alpha = 0.22f), colors.primary)
        SoftButtonTone.Danger -> Triple(colors.error.copy(alpha = 0.10f), colors.error.copy(alpha = 0.24f), colors.error)
    }
    val tint = if (enabled) content else content.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .heightIn(min = if (compact) 42.dp else 56.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, edge, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Accept / Reject / Block for a message request. Reject only removes the request
 * (a new message from them makes a new one); Block also drops everything they
 * send from now on.
 */
@Composable
fun MessageRequestActions(
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientButton(text = stringResource(R.string.components_request_accept), onClick = onAccept, compact = true, modifier = Modifier.weight(1.2f))
        SoftButton(text = stringResource(R.string.components_request_reject), onClick = onReject, compact = true, modifier = Modifier.weight(1f))
        SoftButton(
            text = stringResource(R.string.components_request_block),
            onClick = onBlock,
            tone = SoftButtonTone.Danger,
            compact = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A glowing gradient floating action button that shows its label while
 * [expanded] (e.g. at the top of a list) and shrinks to the icon otherwise.
 */
@Composable
fun GradientFab(
    text: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aurora = Aurora.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .shadow(20.dp, shape, ambientColor = aurora.glow, spotColor = aurora.glow)
            .background(Brush.linearGradient(aurora.brand), shape)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.White),
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (expanded) null else text,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp),
            )
        }
    }
}
