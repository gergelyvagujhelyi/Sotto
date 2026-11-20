package com.sotto.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sotto.app.ui.theme.Aurora
import com.sotto.app.ui.theme.AuroraPink
import com.sotto.app.ui.theme.AuroraSky
import com.sotto.app.ui.theme.AuroraTeal
import com.sotto.app.ui.theme.LocalReduceMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * A filled [shape] that, while [active], is ringed by a slowly turning spectrum
 * with a soft halo — the focused-input glow. Idle it is a plain hairline, and the
 * rotation only runs while active, so an idle field costs no frames.
 */
@Composable
fun GlowingOutline(
    active: Boolean,
    shape: Shape,
    fill: Color,
    idleBorder: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val spectrum = Aurora.colors.spectrum
    val glow = animateFloatAsState(if (active) 1f else 0f, tween(400), label = "glow")
    val angle = remember { Animatable(0f) }
    LaunchedEffect(active) {
        // With animations turned off (duration scale 0) every turn would finish in a
        // single frame and the loop would redraw nonstop; the ring stays still then.
        val animationsOn = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
        if (active && animationsOn) {
            while (true) {
                angle.animateTo(angle.value + 360f, tween(6_000, easing = LinearEasing))
            }
        }
    }
    Box(
        modifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val wideHalo = Stroke(width = 14.dp.toPx())
            val halo = Stroke(width = 6.dp.toPx())
            val ring = Stroke(width = 1.5.dp.toPx())
            val hairline = Stroke(width = 1.dp.toPx())
            // One sweep shader per size; a frame only turns it, so nothing is
            // allocated while the ring spins.
            val pivot = size.center
            val sweep = SweepGradientShader(pivot, spectrum)
            val sweepBrush = ShaderBrush(sweep)
            val turn = android.graphics.Matrix()
            onDrawWithContent {
                val g = glow.value
                if (g > 0f) {
                    turn.setRotate(angle.value, pivot.x, pivot.y)
                    sweep.setLocalMatrix(turn)
                    drawOutline(outline, sweepBrush, alpha = 0.10f * g, style = wideHalo)
                    drawOutline(outline, sweepBrush, alpha = 0.22f * g, style = halo)
                }
                drawOutline(outline, fill)
                if (g < 1f) drawOutline(outline, idleBorder, alpha = 1f - g, style = hairline)
                if (g > 0f) drawOutline(outline, sweepBrush, alpha = g, style = ring)
                drawContent()
            }
        },
        content = content,
    )
}

/**
 * A softly breathing glass sphere with coloured light swirling inside it, for
 * hero moments (onboarding, empty states, generating keys). [speed] scales the
 * swirl. With [animated] false it is drawn still, for screens that may be left
 * open indefinitely, where a constant animation would only cost battery.
 */
@Composable
fun GlowOrb(
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
    speed: Float = 1f,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val aurora = Aurora.colors
    val spin: State<Float>
    val breathe: State<Float>
    if (animated && !LocalReduceMotion.current) {
        val transition = rememberInfiniteTransition(label = "orb")
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween((10_000 / speed).toInt(), easing = LinearEasing)),
            label = "orbSpin",
        )
        breathe = transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(2_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "orbBreathe",
        )
    } else {
        spin = remember { mutableFloatStateOf(40f) }
        breathe = remember { mutableFloatStateOf(1f) }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    // Every gradient is built once per size; a frame only scales the
                    // orb and moves the lights, so the animation allocates nothing.
                    val c = this.size.center
                    val r = this.size.minDimension / 2f
                    val body = r * 0.8f
                    val halo = Brush.radialGradient(
                        0f to aurora.glow.copy(alpha = 0.55f),
                        0.5f to aurora.glow.copy(alpha = 0.16f),
                        1f to Color.Transparent,
                        center = c,
                        radius = r * 1.9f,
                    )
                    val base = Brush.linearGradient(
                        aurora.brand,
                        start = Offset(c.x - body, c.y - body),
                        end = Offset(c.x + body, c.y + body),
                    )
                    // Centred on the origin, so a translate positions them.
                    val lights = listOf(AuroraSky, AuroraPink, AuroraTeal).map { color ->
                        Brush.radialGradient(
                            0f to color.copy(alpha = 0.8f),
                            1f to Color.Transparent,
                            center = Offset.Zero,
                            radius = body * 0.85f,
                        )
                    }
                    val glossCenter = Offset(c.x - body * 0.35f, c.y - body * 0.45f)
                    val gloss = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                        center = glossCenter,
                        radius = body * 0.7f,
                    )
                    val sphere = Path().apply { addOval(Rect(c, body)) }
                    val rim = Stroke(width = 1.dp.toPx())
                    onDrawBehind {
                        scale(breathe.value, pivot = c) {
                            drawCircle(halo, radius = r * 1.9f, center = c)
                            clipPath(sphere) {
                                drawCircle(base, radius = body, center = c)
                                lights.forEachIndexed { i, light ->
                                    val a = Math.toRadians((spin.value + i * 120f).toDouble())
                                    translate(
                                        left = c.x + cos(a).toFloat() * body * 0.5f,
                                        top = c.y + sin(a).toFloat() * body * 0.5f,
                                    ) {
                                        drawCircle(light, radius = body * 0.85f, center = Offset.Zero)
                                    }
                                }
                                drawCircle(gloss, radius = body * 0.7f, center = glossCenter)
                            }
                            drawCircle(Color.White.copy(alpha = 0.22f), radius = body, center = c, style = rim)
                        }
                    }
                },
        )
        content()
    }
}

/**
 * Text with a highlight sweeping across it — the "working on it" shimmer.
 * The sweep is drawn in the draw phase, so it doesn't recompose every frame.
 */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    highlight: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null,
) {
    val progress = if (LocalReduceMotion.current) {
        remember { mutableFloatStateOf(0.5f) }
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_700, easing = LinearEasing)),
            label = "shimmerProgress",
        )
    }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                val band = size.width * 0.35f
                // One gradient spanning -band..band; a frame only slides it along.
                val sweep = Brush.horizontalGradient(
                    listOf(Color.Transparent, highlight, Color.Transparent),
                    startX = -band,
                    endX = band,
                )
                onDrawWithContent {
                    drawContent()
                    val x = -band + (size.width + 2 * band) * progress.value
                    translate(left = x) {
                        drawRect(sweep, topLeft = Offset(-x, 0f), size = size, blendMode = BlendMode.SrcAtop)
                    }
                }
            },
    )
}

/** Text painted with a horizontal gradient. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun GradientText(
    text: String,
    style: TextStyle,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = Brush.horizontalGradient(colors)),
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
