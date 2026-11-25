package com.sotto.app.group

import com.sotto.app.domain.model.ReactionPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What one member's phone sends another about a group (ADR-32), inside an ordinary
 * pairwise, ratchet-encrypted and signed message: `"\u0001sotto/group/v1:" + JSON`.
 *
 * Every packet names its group, its own id, its sender and its one recipient, the roster
 * epoch its sender held, and when it was made, all inside the encryption, so the relay sees
 * only an ordinary envelope. The sender and recipient must match the envelope's
 * authenticated ones ([GroupPacketCodec.decode] checks the form; the receiver checks those).
 * Older apps show the packet as "needs a newer Sotto" (UnknownPayload).
 */
@Serializable
data class GroupPacket(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("k") val kind: String,
    @SerialName("g") val groupId: String,
    /** Random, 32 hex; with [sender] it names a message for every member (see [GroupIds.rowId]). */
    @SerialName("id") val id: String,
    @SerialName("s") val sender: String,
    @SerialName("to") val to: String,
    @SerialName("e") val epoch: Long,
    @SerialName("ts") val timestamp: Long,
    /** [KIND_MSG]: what a 1:1 message would carry (text, or a media or file payload). */
    @SerialName("b") val body: String? = null,
    /** [KIND_MSG]: the group message it replies to, and a quote of it. */
    @SerialName("re") val replyTo: GroupRef? = null,
    @SerialName("rq") val replyQuote: String? = null,
    /** [KIND_REACT]: the group message reacted to, and the emoji ("" takes it back). */
    @SerialName("tg") val target: GroupRef? = null,
    @SerialName("em") val emoji: String? = null,
    /** [KIND_INVITE] and [KIND_ROSTER]: the signed roster. */
    @SerialName("r") val roster: GroupRoster? = null,
    /** [KIND_ACCEPT]: the name the new member chose. */
    @SerialName("n") val name: String? = null,
) {
    companion object {
        const val VERSION = 1
        const val KIND_MSG = "msg"
        const val KIND_REACT = "react"
        const val KIND_INVITE = "invite"
        const val KIND_ACCEPT = "accept"
        const val KIND_REJECT = "reject"
        const val KIND_ROSTER = "roster"
        const val KIND_LEAVE = "leave"
        val KINDS = setOf(KIND_MSG, KIND_REACT, KIND_INVITE, KIND_ACCEPT, KIND_REJECT, KIND_ROSTER, KIND_LEAVE)
    }
}

/** A group message: its sender and its id. */
@Serializable
data class GroupRef(
    @SerialName("s") val sender: String,
    @SerialName("id") val id: String,
)

object GroupPacketCodec {
    const val PREFIX = "\u0001sotto/group/v1:"

    /**
     * The largest packet taken, in characters: an inline photo or voice message (about 190 KB
     * encoded, MediaPayload) with room for its JSON; a roster of 32 is a few KB.
     */
    const val MAX_CHARS = 262_144
    const val MAX_BODY_CHARS = 240_000
    const val MAX_QUOTE = 200
    /** How far a packet's time may be ahead of this phone's clock. */
    const val MAX_FUTURE_MS = 24L * 60 * 60 * 1000
    /** How old a packet may be: the relay keeps undelivered messages 30 days. */
    const val MAX_AGE_MS = 31L * 24 * 60 * 60 * 1000

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
    }
    private val ID = Regex("^[0-9a-f]{32}$")

    fun isGroup(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    fun encode(packet: GroupPacket): String = PREFIX + json.encodeToString(GroupPacket.serializer(), packet)

    sealed interface Decoded {
        data class Ok(val packet: GroupPacket) : Decoded
        data class Invalid(val reason: String) : Decoded
        /** A later version of the group protocol: this app can't read it. */
        data object Unsupported : Decoded
    }

    /** Parses and checks the form of a packet; never throws. [now] bounds its time. */
    fun decode(plaintext: String, now: Long): Decoded {
        if (!isGroup(plaintext)) return Decoded.Invalid("not a group packet")
        if (plaintext.length > MAX_CHARS) return Decoded.Invalid("too large")
        val packet = try {
            json.decodeFromString(GroupPacket.serializer(), plaintext.substring(PREFIX.length))
        } catch (e: Exception) {
            // A newer version may add fields; tell it apart from garbage by its version.
            val version = Regex("\"v\"\\s*:\\s*(\\d+)").find(plaintext)?.groupValues?.get(1)?.toIntOrNull()
            return if (version != null && version > GroupPacket.VERSION) Decoded.Unsupported else Decoded.Invalid("malformed")
        }
        if (packet.version != GroupPacket.VERSION) {
            return if (packet.version > GroupPacket.VERSION) Decoded.Unsupported else Decoded.Invalid("version")
        }
        check(packet, now)?.let { return Decoded.Invalid(it) }
        return Decoded.Ok(packet)
    }

    /** Null when [p] is well formed for its kind. */
    private fun check(p: GroupPacket, now: Long): String? {
        if (p.kind !in GroupPacket.KINDS) return "kind"
        if (!RosterRules.isGroupId(p.groupId) || !ID.matches(p.id)) return "ids"
        if (!RosterRules.isUserId(p.sender) || !RosterRules.isUserId(p.to) || p.sender == p.to) return "sender or recipient"
        if (p.epoch !in 1..GroupRoster.MAX_EPOCH) return "epoch"
        if (p.timestamp > now + MAX_FUTURE_MS || p.timestamp < now - MAX_AGE_MS) return "time"
        val forMsg = p.body != null || p.replyTo != null || p.replyQuote != null
        val forReact = p.target != null || p.emoji != null
        val hasRoster = p.roster != null
        val hasName = p.name != null
        when (p.kind) {
            GroupPacket.KIND_MSG -> {
                if (forReact || hasRoster || hasName) return "fields"
                val body = p.body ?: return "body"
                if (body.length > MAX_BODY_CHARS) return "body too large"
                if ((p.replyTo == null) != (p.replyQuote == null)) return "reply"
                p.replyTo?.let { if (!refOk(it)) return "reply" }
                if ((p.replyQuote?.length ?: 0) > MAX_QUOTE) return "quote"
                if (body.isEmpty()) return "empty"
            }
            GroupPacket.KIND_REACT -> {
                if (forMsg || hasRoster || hasName) return "fields"
                val target = p.target ?: return "target"
                if (!refOk(target)) return "target"
                val emoji = p.emoji ?: return "emoji"
                if (emoji.isNotEmpty() && !ReactionPayload.isEmoji(emoji)) return "emoji"
            }
            GroupPacket.KIND_INVITE, GroupPacket.KIND_ROSTER -> {
                if (forMsg || forReact || hasName) return "fields"
                val roster = p.roster ?: return "roster"
                if (roster.groupId != p.groupId || roster.epoch != p.epoch) return "roster binding"
                if (roster.issuer != p.sender) return "roster binding"
                RosterRules.validate(roster)?.let { return "roster: $it" }
            }
            GroupPacket.KIND_ACCEPT -> {
                if (forMsg || forReact || hasRoster) return "fields"
                val name = p.name ?: return "name"
                if (name.length > GroupRoster.MAX_NAME) return "name"
            }
            GroupPacket.KIND_REJECT, GroupPacket.KIND_LEAVE -> {
                if (forMsg || forReact || hasRoster || hasName) return "fields"
            }
        }
        return null
    }

    private fun refOk(ref: GroupRef) = RosterRules.isUserId(ref.sender) && ID.matches(ref.id)
}

/** The ids group messages and their copies go by. */
object GroupIds {
    private val random = SecureRandom()

    /** A new random group or packet id: 32 hex (128 bits). */
    fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The id every member's phone stores group message [id] from [sender] in [groupId] under.
     * Derived from all three, so one member can't name (and so replace or hide) another's
     * message. Starts with "g", which a relay id (hex) never does.
     */
    fun rowId(groupId: String, sender: String, id: String): String =
        "g" + sha256Hex("Sotto-group-msg|$groupId|$sender|$id").take(31)

    /**
     * The relay's client message id for the copy of packet [packetId] that goes to [recipient]:
     * one per recipient, the same on every retry, so the relay keeps one copy each (messages.py).
     */
    fun copyClientId(packetId: String, recipient: String): String =
        sha256Hex("Sotto-group-copy|$packetId|$recipient").take(32)

    internal fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
