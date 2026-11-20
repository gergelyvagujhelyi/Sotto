package com.sotto.app.ui.theme

import androidx.compose.ui.graphics.Color

// The aurora spectrum: one violet → blue → cyan light runs through the whole app —
// primary actions, sent bubbles, avatars, focus glows and the backdrop light.
val AuroraViolet = Color(0xFF7C5CFF)
val AuroraIndigo = Color(0xFF5865F2)
val AuroraBlue = Color(0xFF3A72F0)
val AuroraSky = Color(0xFF38BDF8)
val AuroraCyan = Color(0xFF22D3EE)
val AuroraTeal = Color(0xFF2DD4BF)
val AuroraPink = Color(0xFFF062C0)
val AuroraMint = Color(0xFF34D399)

// The brand gradient's steps for the light theme: deeper, so white text on them
// keeps ~4.5:1 contrast.
val AuroraVioletDeep = Color(0xFF6A4CF0)
val AuroraIndigoDeep = Color(0xFF4D5BE8)
val AuroraBlueDeep = Color(0xFF2F6FE8)

// Read ticks on a sent (gradient) bubble: bright and saturated, so "read" stands
// apart from the dim ticks of sent and delivered by hue and brightness, not only tint.
val ReadTick = Color(0xFF5EF2D6)

// Night neutrals: blue-black rather than grey, so the aurora reads as light.
internal val Night0 = Color(0xFF07070C)
internal val Night1 = Color(0xFF0B0B12)
internal val Night2 = Color(0xFF12121B)
internal val Night3 = Color(0xFF171723)
internal val Night4 = Color(0xFF1D1D2B)
internal val Night5 = Color(0xFF262637)
internal val NightOutline = Color(0xFF3A3A52)
internal val NightOutlineVariant = Color(0xFF262638)
internal val NightText = Color(0xFFF2F2F8)
internal val NightTextMuted = Color(0xFFA3A3B8)

// Day neutrals: a faint lavender cast keeps the whites from looking clinical.
internal val Day0 = Color(0xFFFFFFFF)
internal val Day1 = Color(0xFFF7F7FC)
internal val Day2 = Color(0xFFF1F0FA)
internal val Day3 = Color(0xFFECEBF6)
internal val Day4 = Color(0xFFE6E5F2)
internal val Day5 = Color(0xFFDFDEEC)
internal val DayOutline = Color(0xFFC6C5D8)
internal val DayOutlineVariant = Color(0xFFE3E2EE)
internal val DayText = Color(0xFF12121A)
internal val DayTextMuted = Color(0xFF5E5E73)

// Call actions keep their universal meaning in both themes.
val CallRed = listOf(Color(0xFFFF4D6A), Color(0xFFE5244B))
val CallGreen = listOf(Color(0xFF3BE0A0), Color(0xFF10B981))
