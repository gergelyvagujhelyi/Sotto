package com.sotto.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while animations are reduced: the user's "Reduce animations" setting, or Android's
 * "Remove animations". Decorative motion (send animation, drifting lights, pulsing rings,
 * shimmer) holds still; motion that tells something, like the reconnecting spinner, stays.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Android's "Remove animations" (accessibility or developer options): animator scale 0. */
fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
