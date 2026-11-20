package com.sotto.app.domain.model

data class PrivacySettings(
    val lockScreenPreview: LockScreenPreviewMode = LockScreenPreviewMode.SHOW_ALL,
    val disappearingMessagesDefault: DisappearingDuration = DisappearingDuration.OFF,
    val readReceipts: Boolean = true,
    val screenSecurity: Boolean = false,
    /** Delete messages older than this many days; 0 keeps them forever. */
    val localDataRetentionDays: Int = 0,
    /**
     * Keep a connection to the relay open while the app is closed (a foreground service), so
     * messages and calls arrive on phones without Google push.
     */
    val backgroundConnection: Boolean = false,
    /**
     * Calls with a contact who has this on too go straight between the phones instead of
     * through the relay. Both then see each other's IP addresses. On by default since v0.5.8,
     * the owner's choice (ARCHITECTURE.md, ADR-8); turning it off keeps calls relayed.
     */
    val directCalls: Boolean = true,
    /**
     * Calls with someone who has this on too send the voice at a higher bitrate (ADR-21). The
     * lower of the two phones' choice wins, so it's off for the call if either has it off.
     */
    val highQualityCalls: Boolean = false,
    /**
     * Lift a quiet voice in calls to a normal level (ADR-23). Off by default, the owner's choice:
     * voices play as the phone plays them, and Louder on the call screen lifts one person's.
     */
    val levelVoices: Boolean = false,
    /**
     * The microphone goes through WebRTC's echo cancellation, noise suppression and automatic
     * gain instead of the phone's own (ADR-25). Off by default: the phone's own, tuned for it.
     */
    val webRtcVoiceProcessing: Boolean = false,
    /** No send animation, and still backgrounds and call screen (see LocalReduceMotion). */
    val reduceAnimations: Boolean = false,
)

enum class LockScreenPreviewMode {
    SHOW_ALL,
    SENDER_ONLY,
    HIDE_BODY,
    HIDE_ALL,
}

enum class DisappearingDuration {
    OFF,
    MINUTES_5,
    HOURS_1,
    HOURS_24,
    DAYS_7,
    DAYS_30;

    val millis: Long?
        get() = when (this) {
            OFF -> null
            MINUTES_5 -> 5L * 60 * 1000
            HOURS_1 -> 60L * 60 * 1000
            HOURS_24 -> 24L * 60 * 60 * 1000
            DAYS_7 -> 7L * 24 * 60 * 60 * 1000
            DAYS_30 -> 30L * 24 * 60 * 60 * 1000
        }
}
