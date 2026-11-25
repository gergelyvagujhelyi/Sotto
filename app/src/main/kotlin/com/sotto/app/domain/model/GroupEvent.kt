package com.sotto.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Something that happened in a group, kept as a [MessageState.GROUP_EVENT] row (its plaintext)
 * and shown in the chat in the user's language. Names are stored as they were then, so a
 * member who leaves still shows by name.
 */
@Serializable
data class GroupEvent(
    @SerialName("t") val type: String,
    /** The members it's about, and their names then. */
    @SerialName("u") val users: List<String> = emptyList(),
    @SerialName("n") val names: List<String> = emptyList(),
    /** A new title, or a timer in ms. */
    @SerialName("x") val value: String = "",
) {
    companion object {
        const val CREATED = "created"
        const val JOINED = "joined"
        const val INVITED = "invited"
        const val LEFT = "left"
        const val REMOVED = "removed"
        const val DECLINED = "declined"
        const val TITLE = "title"
        const val TIMER = "timer"
        const val ADMIN = "admin"
        const val ENDED = "ended"
        const val YOU_REMOVED = "you_removed"
        const val YOU_LEFT = "you_left"
        /** The admin's account is gone without a handover. */
        const val FROZEN = "frozen"
        /** A member's keys don't match what the admin vouched for. */
        const val KEYS_MISMATCH = "keys_mismatch"
        /** The user joined (their first roster as a member). */
        const val YOU_JOINED = "you_joined"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(event: GroupEvent): String = json.encodeToString(serializer(), event)

        fun decode(plaintext: String): GroupEvent? = runCatching { json.decodeFromString(serializer(), plaintext) }.getOrNull()
    }
}
