package com.sotto.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sotto.app.R

// Inter, bundled as a single variable font (SIL Open Font License; the licence
// ships in assets/licenses/inter-OFL.txt). It is bundled rather than downloaded
// so no font request ever leaves the device. Body text uses the default optical
// size; headlines use opsz 32 ("Inter Display"), tighter and crisper when large.
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight, opticalSize: Float? = null) = Font(
    resId = R.font.inter_variable,
    weight = weight,
    variationSettings = if (opticalSize == null) {
        FontVariation.Settings(FontVariation.weight(weight.weight))
    } else {
        FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.Setting("opsz", opticalSize),
        )
    },
)

val InterText = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

val InterDisplay = FontFamily(
    inter(FontWeight.Medium, opticalSize = 32f),
    inter(FontWeight.SemiBold, opticalSize = 32f),
    inter(FontWeight.Bold, opticalSize = 32f),
    inter(FontWeight.ExtraBold, opticalSize = 32f),
)

private fun display(weight: FontWeight, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = InterDisplay,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun text(weight: FontWeight, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = InterText,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val AppTypography = Typography(
    displayLarge = display(FontWeight.ExtraBold, 48, 54, -1.4),
    displayMedium = display(FontWeight.Bold, 40, 46, -1.1),
    displaySmall = display(FontWeight.Bold, 34, 40, -0.9),
    headlineLarge = display(FontWeight.Bold, 30, 36, -0.7),
    headlineMedium = display(FontWeight.Bold, 28, 34, -0.6),
    headlineSmall = display(FontWeight.SemiBold, 22, 28, -0.35),
    titleLarge = text(FontWeight.SemiBold, 20, 26, -0.3),
    titleMedium = text(FontWeight.SemiBold, 16, 22, -0.15),
    titleSmall = text(FontWeight.SemiBold, 14, 20, -0.05),
    bodyLarge = text(FontWeight.Normal, 16, 23, -0.15),
    bodyMedium = text(FontWeight.Normal, 14, 20, -0.05),
    bodySmall = text(FontWeight.Normal, 12, 17, 0.0),
    labelLarge = text(FontWeight.SemiBold, 15, 20, -0.1),
    labelMedium = text(FontWeight.Medium, 12, 16, 0.1),
    labelSmall = text(FontWeight.Medium, 11, 14, 0.2),
)
