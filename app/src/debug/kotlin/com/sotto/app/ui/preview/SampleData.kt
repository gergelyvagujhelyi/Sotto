package com.sotto.app.ui.preview

import com.sotto.app.domain.model.BlockedUser
import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallPath
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.model.Conversation
import com.sotto.app.domain.model.InviteLink
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.ReplyRef
import com.sotto.app.ui.settings.InviteShare
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Made-up people and messages for previews. Nothing here is real. */
internal object SampleData {
    private val tz = TimeZone.currentSystemDefault()
    private val today = Clock.System.todayIn(tz)
    private val now = Clock.System.now()

    private fun at(daysAgo: Int, hour: Int, minute: Int): Instant =
        LocalDateTime(today.minus(daysAgo, DateTimeUnit.DAY), LocalTime(hour, minute)).toInstant(tz)

    const val SHARE_CODE = "hT7wQ2xLp9Vr4KzN"
    const val MY_NAME = "Elsa Lund"
    const val PQC_INFO = "ML-KEM-768 + X25519 + AES-256-GCM"

    /** A made-up relay (example.com is reserved for examples) and invite. */
    const val RELAY = "https://relay.example.com"
    const val RELAY_HOST = "relay.example.com"
    const val INVITE_CODE = "K7QX-M2PA-9TRB"

    val invite = InviteShare(
        link = InviteLink(RELAY, "K7QXM2PA9TRB", SHARE_CODE).toLink(),
        expiresAt = (now + (7 * 24).hours).toString(),
    )

    val conversations = listOf(
        conversation("c1", "u-maja", "Maja Sjöberg", "Fingerprints match. We're good ✅", now - 3.minutes, unread = 2),
        conversation("c2", "u-axel", "Axel Nyström", "Sent you the build notes", now - 48.minutes),
        conversation("c3", "7f3a9c21", "7f3a9c21", "Talk tomorrow?", now - 5.hours),
        conversation("c4", "u-ebba", "Ebba Holm", "See you at the meetup 👋", at(1, 19, 42), unread = 1),
        conversation("c5", "u-viktor", "Viktor Bergström", "Ha, quantum-proof memes", at(3, 9, 15)),
        conversation("c6", "u-ingrid", "Ingrid Åkesson", "Thanks! Rotated my keys, all set.", at(40, 11, 0)),
        conversation("c7", "u-emil", "Emil Dahl", "👍", at(70, 16, 20)),
    )

    val requests = listOf(
        conversation(
            "r1", "u-tilda", "Tilda Lindqvist",
            "Hi! Got your code at the meetup — is this the right one?",
            now - 12.minutes, unread = 1, approved = false,
        ),
    )

    val messages: List<Message> = listOf(
        message("m1", at(1, 21, 14), "Did you get the new build running?", outgoing = false),
        message("m2", at(1, 21, 15), "Yes! The post-quantum handshake took 40 ms 🚀", outgoing = true, state = MessageState.SEEN),
        message("m3", at(1, 21, 15), "Barely noticeable.", outgoing = true, state = MessageState.SEEN),
        message("m4", at(1, 21, 30), "754", outgoing = true, state = MessageState.CALL_OUTGOING),
        message("m5", at(0, 9, 2), "", outgoing = false, state = MessageState.KEY_ROTATED_REMOTE),
        message("m6", at(0, 9, 5), "Rotated my keys this morning, new phone 📱", outgoing = false),
        message("m7", at(0, 9, 5), "Can you verify my code again?", outgoing = false),
        message("m8", at(0, 9, 7), "Done. Fingerprints match ✅", outgoing = true, state = MessageState.DELIVERED),
        message("m9", at(0, 9, 8), "Lunch at 12?", outgoing = true, state = MessageState.FAILED),
        message("m10", at(0, 9, 10), "0", outgoing = false, state = MessageState.CALL_MISSED),
        message(
            "m11", at(0, 9, 12),
            "Sounds good. And since the relay only ever sees encrypted envelopes, we can " +
                "finally stop worrying about metadata in the group project.",
            outgoing = false,
        ),
        message("m12", at(0, 9, 13), "On my way", outgoing = true, state = MessageState.SENDING),
    )

    /** A chat with replies and links, for the "chat-reply" sample. */
    val replyMessages: List<Message> = listOf(
        message("r1", at(0, 9, 2), "Fika at Vete-Katten at 3? Their menu: https://www.vetekatten.se/meny", outgoing = false),
        message("r2", at(0, 9, 4), "Can't at 3, I have the dentist 🦷", outgoing = true, state = MessageState.SEEN),
        message("r3", at(0, 9, 5), "Then after work?", outgoing = false),
        message("r4", at(0, 9, 6), "Yes! 5:30 works", outgoing = true, state = MessageState.SEEN)
            .copy(replyTo = ReplyRef("r3", "Then after work?", toOwn = false)),
        message("r5", at(0, 9, 8), "Poor you. Good luck!", outgoing = false)
            .copy(replyTo = ReplyRef("r2", "Can't at 3, I have the dentist 🦷", toOwn = true)),
        message("r6", at(0, 9, 9), "The cardamom buns are the best", outgoing = true, state = MessageState.DELIVERED)
            .copy(replyTo = ReplyRef("r1", "Fika at Vete-Katten at 3? Their menu: https://www.vetekatten.se/meny", toOwn = false)),
    )

    val contacts = listOf(
        contact("u-maja", "Maja Sjöberg"),
        contact("u-axel", "Axel Nyström"),
        contact("7f3a9c21", "7f3a9c21"),
        contact("u-ebba", "Ebba Holm"),
    )

    val blockedUsers = listOf(BlockedUser(userId = "u-spam", displayName = "Quick Prize Desk"))

    val incomingCall = CallState(
        callId = "call-1",
        peerId = "u-maja",
        peerDisplayName = "Maja Sjöberg",
        direction = CallDirection.INCOMING,
        status = CallStatus.RINGING,
        pqcEstablished = true,
    )

    val connectedCall = incomingCall.copy(
        direction = CallDirection.OUTGOING,
        status = CallStatus.CONNECTED,
        connectedAt = System.currentTimeMillis() - 754_000,
        path = CallPath.DIRECT,
        highQuality = true,
        louder = true,
    )

    val outgoingCall = incomingCall.copy(
        peerId = "7f3a9c21",
        peerDisplayName = "7f3a9c21",
        direction = CallDirection.OUTGOING,
        status = CallStatus.CONNECTING,
        relayUnavailable = true,
        isNewContact = true,
    )

    private fun conversation(
        id: String,
        contactId: String,
        name: String,
        preview: String,
        time: Instant,
        unread: Int = 0,
        approved: Boolean = true,
    ) = Conversation(
        id = id,
        contactId = contactId,
        contactDisplayName = name,
        lastMessagePreview = preview,
        lastMessageTimestamp = time,
        unreadCount = unread,
        isEncrypted = true,
        isApproved = approved,
    )

    private fun message(
        id: String,
        time: Instant,
        text: String,
        outgoing: Boolean,
        state: MessageState = MessageState.DELIVERED,
    ) = Message(
        id = id,
        conversationId = "c1",
        senderId = if (outgoing) "me" else "u-maja",
        recipientId = if (outgoing) "u-maja" else "me",
        plaintext = text,
        state = state,
        timestamp = time,
        isOutgoing = outgoing,
    )

    private fun contact(id: String, name: String) = Contact(
        userId = id,
        displayName = name,
        publicIdentityKey = ByteArray(0),
        publicSigningKey = ByteArray(0),
    )
}
