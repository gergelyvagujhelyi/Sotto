package com.sotto.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sotto.app.ui.theme.AuroraMint
import com.sotto.app.ui.theme.AuroraPink
import com.sotto.app.ui.theme.AuroraSky
import com.sotto.app.ui.theme.AuroraViolet
import com.sotto.app.ui.theme.InterDisplay

// Brand colours where they fit, plus a few avatar-only accents for variety.
private val AvatarGradients = listOf(
    listOf(AuroraViolet, AuroraSky),
    listOf(AuroraPink, AuroraViolet),
    listOf(AuroraMint, Color(0xFF0EA5E9)),
    listOf(Color(0xFFFB923C), AuroraPink),
    listOf(Color(0xFF60A5FA), Color(0xFFA78BFA)),
    listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
    listOf(Color(0xFF14B8A6), Color(0xFF6366F1)),
    listOf(Color(0xFFE879F9), Color(0xFFFB7185)),
)

/**
 * A glossy gradient disc with initials. The gradient is picked from [seed] (use
 * the contact id), so a contact keeps the same colours on every screen.
 */
@Composable
fun GradientAvatar(
    name: String,
    modifier: Modifier = Modifier,
    seed: String = name,
    size: Dp = 48.dp,
) {
    val colors = remember(seed) { AvatarGradients[Math.floorMod(seed.hashCode(), AvatarGradients.size)] }
    // Sized from dp, not sp, so initials stay inside the disc at any font scale.
    val fontSize = with(LocalDensity.current) { (size * 0.38f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .drawWithContent {
                // A soft top-left highlight gives the disc some depth.
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.28f),
                        1f to Color.Transparent,
                        center = Offset(this.size.width * 0.3f, this.size.height * 0.2f),
                        radius = this.size.minDimension * 0.75f,
                    ),
                )
                drawContent()
            }
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            color = Color.White,
            fontFamily = InterDisplay,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            maxLines = 1,
            // The line exactly as tall as the letters, without font padding: the app's line height
            // (24 sp) around a 6 sp letter put it off centre in the small read circles.
            style = androidx.compose.ui.text.TextStyle(
                lineHeight = fontSize,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

/** Up to two initials; surrogate-pair safe so emoji names don't render half a glyph. */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun first(word: String) = word.substring(0, Character.charCount(word.codePointAt(0)))
    return when (words.size) {
        0 -> "?"
        1 -> first(words[0]).uppercase()
        else -> (first(words[0]) + first(words[1])).uppercase()
    }
}
