package com.sotto.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.sotto.app.ui.theme.Aurora
import com.sotto.app.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Where the light sits: everywhere, behind a screen header, or faint in the corners. */
enum class AuroraStyle { Full, Header, Subtle }

/**
 * The app's backdrop: the theme background with a few large, soft radial lights
 * in the brand colours behind [content]. Like a Surface, it sets the content
 * colour, so text on the (transparent) screens above it gets the right colour.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    style: AuroraStyle = AuroraStyle.Full,
    animated: Boolean = false,
    color: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalContentColor provides contentColorFor(color)) {
        Box(modifier.background(color)) {
            AuroraLights(Modifier.matchParentSize(), style, animated)
            content()
        }
    }
}

/**
 * Just the lights. Static by default; [animated] lets them drift slowly. They sit
 * on their own layer and are read in the draw phase only, so nothing on top of
 * them recomposes or re-records when they move.
 */
@Composable
fun AuroraLights(
    modifier: Modifier = Modifier,
    style: AuroraStyle = AuroraStyle.Full,
    animated: Boolean = false,
) {
    val aurora = Aurora.colors
    val phase: State<Float>? = if (animated && !LocalReduceMotion.current) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 26_000, easing = LinearEasing)),
            label = "auroraPhase",
        )
    } else {
        null
    }
    Spacer(
        modifier
            .graphicsLayer()
            .drawWithCache {
                val lights = lightsFor(style)
                // One gradient per light, built once per size and centred on the
                // origin; a frame only moves them, so drifting allocates nothing.
                val radii = FloatArray(lights.size) { lightRadius(lights[it], style, size) }
                val brushes = lights.mapIndexed { i, light ->
                    val color = aurora.lights[light.colorIndex % aurora.lights.size]
                    val alpha = aurora.lightStrength * light.strength
                    Brush.radialGradient(
                        0f to color.copy(alpha = alpha),
                        0.45f to color.copy(alpha = alpha * 0.4f),
                        1f to Color.Transparent,
                        center = Offset.Zero,
                        radius = radii[i],
                    )
                }
                onDrawBehind {
                    val p = phase?.value
                    lights.forEachIndexed { i, light ->
                        val c = lightCenter(light, style, size, p)
                        translate(left = c.x, top = c.y) {
                            drawCircle(brushes[i], radius = radii[i], center = Offset.Zero)
                        }
                    }
                }
            },
    )
}

/**
 * One light: position as a fraction of the screen, radius as a fraction of the
 * screen's long side (header lights use the width for both), brand colour index,
 * strength, and how far it wanders when animated.
 */
private class Light(
    val x: Float,
    val y: Float,
    val radius: Float,
    val colorIndex: Int,
    val strength: Float,
    val driftX: Float = 0.08f,
    val driftY: Float = 0.06f,
    val offset: Float = 0f,
)

private val FullLights = listOf(
    Light(0.10f, 0.06f, 0.80f, 0, 1.00f, offset = 0.00f),
    Light(0.98f, 0.30f, 0.62f, 1, 0.85f, offset = 0.30f),
    Light(0.80f, 0.94f, 0.75f, 2, 0.70f, offset = 0.55f),
    Light(0.02f, 0.72f, 0.55f, 3, 0.55f, offset = 0.80f),
)

private val HeaderLights = listOf(
    Light(0.08f, 0.02f, 0.78f, 0, 1.25f),
    Light(0.95f, 0.10f, 0.66f, 1, 1.00f),
    Light(0.55f, 0.34f, 0.42f, 2, 0.45f),
)

private val SubtleLights = listOf(
    Light(1.02f, 0.00f, 0.60f, 0, 0.50f),
    Light(-0.02f, 1.00f, 0.60f, 2, 0.32f),
)

private fun lightsFor(style: AuroraStyle): List<Light> = when (style) {
    AuroraStyle.Full -> FullLights
    AuroraStyle.Header -> HeaderLights
    AuroraStyle.Subtle -> SubtleLights
}

private fun lightRadius(light: Light, style: AuroraStyle, size: Size): Float = when (style) {
    AuroraStyle.Header -> light.radius * size.width
    else -> light.radius * max(size.width, size.height)
}

/** [phase] is null for a static backdrop: every light sits at its rest position. */
private fun lightCenter(light: Light, style: AuroraStyle, size: Size, phase: Float?): Offset {
    var fx = light.x
    var fy = light.y
    if (phase != null) {
        val t = (phase + light.offset) * 2f * PI.toFloat()
        fx += sin(t) * light.driftX
        fy += cos(t) * light.driftY
    }
    return when (style) {
        AuroraStyle.Header -> Offset(fx * size.width, fy * size.width)
        else -> Offset(fx * size.width, fy * size.height)
    }
}
