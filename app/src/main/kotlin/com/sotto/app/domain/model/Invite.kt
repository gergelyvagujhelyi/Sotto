package com.sotto.app.domain.model

import com.sotto.app.connection.normalizeRelayUrl
import java.net.URLDecoder

/**
 * An invitation to join a relay: its address, a member's one-time code, and that member's
 * share code (so joining also adds them as a contact).
 *
 * As a link it is one https link, the relay's own address with the code (ADR-24):
 * `https://<relay>/invite#c=<code>&s=<share code>`. Tapped, it opens Sotto (Android App
 * Links, on the relay the app is built for) or the relay's invite page, which offers the
 * download and a button that opens Sotto with `sotto://invite?r=<relay>&c=<code>&s=<share code>`.
 * Browsers never send the part after `#`, so the code doesn't reach the relay's web server.
 *
 * Setup also finds a link inside a whole pasted message, and still reads the links of
 * ChatControll, as the app was called before 0.6.0.
 */
data class InviteLink(val relay: String, val code: String, val inviterShareCode: String?) {
    /** The link to send or show as a QR code. */
    fun toLink(): String = buildString {
        append(relay).append(PAGE_PATH).append('#')
        append("c=").append(displayInviteCode(code))
        if (inviterShareCode != null) append("&s=").append(inviterShareCode)
    }

    companion object {
        /** The path of the relay's invite page, and of every invite link. */
        const val PAGE_PATH = "/invite"

        /** The app's own form, which the invite page's button opens. */
        const val PREFIX = "sotto://invite?"

        /** How ChatControll (the app's name before 0.6.0) wrote invite links. */
        const val LEGACY_PREFIX = "chatcontroll://invite?"

        /** The first invite link in [text], or null when there is none (or it's malformed). */
        fun find(text: String, cleartextHosts: Set<String> = emptySet()): InviteLink? {
            val app = listOf(PREFIX, LEGACY_PREFIX)
                .map { text.indexOf(it, ignoreCase = true) to it }
                .filter { it.first >= 0 }
                .minByOrNull { it.first }
            val web = WEB_LINK.find(text)
            if (web != null && (app == null || web.range.first < app.first)) {
                val params = parameters(text.substring(web.range.last + 1, linkEnd(text, web.range.last + 1)))
                val relay = normalizeRelayUrl(web.groupValues[1], cleartextHosts) ?: return null
                return invite(relay, params)
            }
            val (start, prefix) = app ?: return null
            val params = parameters(text.substring(start + prefix.length, linkEnd(text, start)))
            val relay = normalizeRelayUrl(params["r"] ?: return null, cleartextHosts) ?: return null
            return invite(relay, params)
        }

        private fun invite(relay: String, params: Map<String, String>): InviteLink? {
            val code = normalizeInviteCode(params["c"] ?: return null) ?: return null
            val share = params["s"]?.takeIf { SHARE_CODE.matches(it) }
            return InviteLink(relay, code, share)
        }

        /** Where a link in a message ends: at a space or quote, without a sentence's last stop. */
        private fun linkEnd(text: String, from: Int): Int {
            val end = (from until text.length).firstOrNull { text[it].isWhitespace() || text[it] in "\"'<>" } ?: text.length
            return (end downTo from).firstOrNull { it == from || text[it - 1] !in ".,;:!?)" } ?: from
        }

        private fun parameters(query: String): Map<String, String> = query.split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            runCatching { URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()
        }.toMap()

        /** `https://<host>/invite#`: the relay's address in group 1. */
        private val WEB_LINK = Regex("""(?i)\b(https?://[^\s/?#"'<>]+)${PAGE_PATH}#""")

        private val SHARE_CODE = Regex("[A-Za-z0-9_-]{16}")
    }
}

/** The 32 symbols of an invite code (Crockford's base32: no I, L, O or U). */
private const val INVITE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val INVITE_LENGTH = 12

/**
 * An invite code as the relay stores it, from whatever was typed: case, spaces and dashes
 * don't matter, and O reads as 0, I and L as 1. Null when it can't be a code.
 */
fun normalizeInviteCode(text: String): String? {
    val cleaned = text.uppercase().filter { it.isLetterOrDigit() }
        .replace('O', '0').replace('I', '1').replace('L', '1')
    return cleaned.takeIf { it.length == INVITE_LENGTH && it.all { ch -> ch in INVITE_ALPHABET } }
}

/** "K7QXM2PA9TRB" → "K7QX-M2PA-9TRB". */
fun displayInviteCode(code: String): String = code.chunked(4).joinToString("-")

/** What to tell someone whose sign-up the relay refused over the invite; null for other errors. */
fun inviteErrorMessage(detail: String?): String? = when (detail) {
    "invite_required" -> "This relay takes new people only with an invite link. Ask someone on it to send you one."
    "invite_invalid" -> "That invite doesn't work on this relay. Ask for a new one."
    "invite_used" -> "This invite was already used. Ask for a new one."
    "invite_expired" -> "This invite has expired. Ask for a new one."
    else -> null
}

/** Why the relay wouldn't create another invite right now. */
fun inviteLimitMessage(detail: String?): String = when (detail) {
    "invite_limit_open" -> "You have 5 invites nobody has used yet. Wait until someone joins, or until they expire."
    "invite_limit_daily" -> "You've created 10 invites today. Try again tomorrow."
    "invite_rate_limited" -> "Too many at once. Try again in a minute."
    else -> "Couldn't create an invite. Try again."
}
