package com.sotto.app.domain.model

import com.sotto.app.ui.text.UiText

enum class AudioRouteKind { EARPIECE, SPEAKER, WIRED, USB, BLUETOOTH, HEARING_AID }

/**
 * Where a call's audio goes, and where its microphone listens: they switch together.
 * [id] is the device's id (Android 12+), or a fixed one on older versions; [name] is what
 * the user sees, e.g. "Phone", "Speaker" (in the user's language), or a Bluetooth device's own
 * name (a car's too).
 * [key] names the physical device: earbuds that Android lists twice (classic Bluetooth
 * and LE Audio) are one route with one key, and the key survives reconnecting.
 */
data class AudioRoute(val id: Int, val kind: AudioRouteKind, val name: UiText, val key: String)
