package com.sotto.app.group

import com.sotto.app.group.GroupPacketCodec.Decoded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The group wire format (ADR-32): versioned, bounded, every field checked. */
class GroupPacketTest {

    private val now = 1_764_000_000_000L
    private val groupId = GroupIds.newId()
    private val astrid = TestAccount("astrid")

    private fun msg(body: String = "Hej!") = GroupPacket(
        kind = GroupPacket.KIND_MSG, groupId = groupId, id = GroupIds.newId(), sender = "astrid", to = "bjorn",
        epoch = 2, timestamp = now, body = body,
    )

    private fun decode(p: GroupPacket) = GroupPacketCodec.decode(GroupPacketCodec.encode(p), now)
    private fun decodeRaw(json: String) = GroupPacketCodec.decode(GroupPacketCodec.PREFIX + json, now)

    @Test
    fun `every kind survives encoding`() {
        val roster = RosterRules.sign(
            GroupRoster(groupId, 2, "astrid", "astrid", "Fika", 0, members = listOf(astrid.member(), TestAccount("bjorn").member(RosterMember.INVITED)).sortedBy { it.userId }),
            astrid.signatures,
        )
        val packets = listOf(
            msg(),
            msg().copy(replyTo = GroupRef("cecilia", GroupIds.newId()), replyQuote = "Kaffe?"),
            msg().copy(kind = GroupPacket.KIND_REACT, body = null, target = GroupRef("cecilia", GroupIds.newId()), emoji = "❤️"),
            msg().copy(kind = GroupPacket.KIND_REACT, body = null, target = GroupRef("cecilia", GroupIds.newId()), emoji = ""),
            msg().copy(kind = GroupPacket.KIND_INVITE, body = null, roster = roster),
            msg().copy(kind = GroupPacket.KIND_ROSTER, body = null, roster = roster),
            msg().copy(kind = GroupPacket.KIND_ACCEPT, body = null, name = "Björn"),
            msg().copy(kind = GroupPacket.KIND_REJECT, body = null),
            msg().copy(kind = GroupPacket.KIND_LEAVE, body = null),
        )
        for (p in packets) assertEquals(Decoded.Ok(p), decode(p))
    }

    @Test
    fun `malformed packets are refused, never thrown`() {
        val bad = listOf(
            "{", "null", "[]", "{\"v\":1}", "{\"v\":\"1\",\"k\":\"msg\"}",
            GroupPacketCodec.encode(msg()).removePrefix(GroupPacketCodec.PREFIX).replace("\"b\":", "\"zz\":1,\"b\":"),
        )
        for (json in bad) assertTrue(json, decodeRaw(json) is Decoded.Invalid)
        assertTrue(GroupPacketCodec.decode("Hej", now) is Decoded.Invalid)
    }

    @Test
    fun `a later version is told apart from garbage`() {
        val v2 = GroupPacketCodec.encode(msg()).removePrefix(GroupPacketCodec.PREFIX).replace("\"v\":1", "\"v\":2")
        assertEquals(Decoded.Unsupported, decodeRaw(v2))
        val v2WithNewField = v2.replace("\"b\":", "\"poll\":{},\"b\":")
        assertEquals(Decoded.Unsupported, decodeRaw(v2WithNewField))
        val v0 = GroupPacketCodec.encode(msg()).removePrefix(GroupPacketCodec.PREFIX).replace("\"v\":1", "\"v\":0")
        assertTrue(decodeRaw(v0) is Decoded.Invalid)
    }

    @Test
    fun `sizes are bounded`() {
        assertTrue(decode(msg("x".repeat(GroupPacketCodec.MAX_BODY_CHARS + 1))) is Decoded.Invalid)
        assertTrue(decode(msg("x".repeat(GroupPacketCodec.MAX_BODY_CHARS))) is Decoded.Ok)
        assertTrue(GroupPacketCodec.decode(GroupPacketCodec.PREFIX + "x".repeat(GroupPacketCodec.MAX_CHARS), now) is Decoded.Invalid)
        assertTrue(decode(msg().copy(replyTo = GroupRef("cecilia", GroupIds.newId()), replyQuote = "q".repeat(201))) is Decoded.Invalid)
    }

    @Test
    fun `fields that don't belong to a kind are refused`() {
        val wrong = listOf(
            msg().copy(body = null),
            msg().copy(body = ""),
            msg().copy(emoji = "❤️"),
            msg().copy(name = "x"),
            msg().copy(replyTo = GroupRef("cecilia", GroupIds.newId())), // reply without its quote
            msg().copy(kind = GroupPacket.KIND_REACT, body = null, target = GroupRef("cecilia", GroupIds.newId()), emoji = "hello"),
            msg().copy(kind = GroupPacket.KIND_REACT, body = null, emoji = "❤️"),
            msg().copy(kind = GroupPacket.KIND_ACCEPT, body = null),
            msg().copy(kind = GroupPacket.KIND_LEAVE),
            msg().copy(kind = GroupPacket.KIND_ROSTER, body = null),
            msg().copy(kind = "poll"),
        )
        for (p in wrong) assertTrue(p.toString(), decode(p) is Decoded.Invalid)
    }

    @Test
    fun `ids, people, epoch and time are checked`() {
        val wrong = listOf(
            msg().copy(groupId = "abc"),
            msg().copy(id = "ABCDEF"),
            msg().copy(sender = "bjorn"), // to itself
            msg().copy(to = "bad id!"),
            msg().copy(epoch = 0),
            msg().copy(timestamp = now + GroupPacketCodec.MAX_FUTURE_MS + 1),
            msg().copy(timestamp = now - GroupPacketCodec.MAX_AGE_MS - 1),
        )
        for (p in wrong) assertTrue(p.toString(), decode(p) is Decoded.Invalid)
    }

    @Test
    fun `a roster in a packet must be the packet's own group, epoch and sender`() {
        val roster = RosterRules.sign(
            GroupRoster(groupId, 2, "astrid", "astrid", "Fika", 0, members = listOf(astrid.member())),
            astrid.signatures,
        )
        val base = msg().copy(kind = GroupPacket.KIND_ROSTER, body = null, roster = roster)
        assertTrue(decode(base) is Decoded.Ok)
        assertTrue(decode(base.copy(epoch = 3)) is Decoded.Invalid)
        assertTrue(decode(base.copy(groupId = GroupIds.newId())) is Decoded.Invalid)
        assertTrue(decode(base.copy(sender = "cecilia")) is Decoded.Invalid)
        assertTrue(decode(base.copy(roster = roster.copy(members = roster.members.reversed() + roster.members))) is Decoded.Invalid)
    }

    @Test
    fun `a message's row id is the same on every phone, and bound to its group and sender`() {
        val id = GroupIds.newId()
        assertEquals(GroupIds.rowId(groupId, "astrid", id), GroupIds.rowId(groupId, "astrid", id))
        assertNotEquals(GroupIds.rowId(groupId, "astrid", id), GroupIds.rowId(groupId, "bjorn", id))
        assertNotEquals(GroupIds.rowId(groupId, "astrid", id), GroupIds.rowId(GroupIds.newId(), "astrid", id))
        val row = GroupIds.rowId(groupId, "astrid", id)
        assertEquals(32, row.length)
        assertTrue(row.startsWith("g"))
    }

    @Test
    fun `each recipient's copy has its own client id, the same on every retry`() {
        val packetId = GroupIds.newId()
        val ids = listOf("bjorn", "cecilia", "dagny").map { GroupIds.copyClientId(packetId, it) }
        assertEquals(3, ids.toSet().size)
        assertEquals(ids, listOf("bjorn", "cecilia", "dagny").map { GroupIds.copyClientId(packetId, it) })
        assertTrue(ids.all { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })
        assertNotEquals(ids[0], GroupIds.copyClientId(GroupIds.newId(), "bjorn"))
    }
}
