package com.sotto.app.data.repository

import com.sotto.app.crypto.ContactKeyVerifier
import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.data.local.entity.GroupEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.media.EncryptedFile
import com.sotto.app.data.media.FileDrop
import com.sotto.app.data.media.MediaStore
import com.sotto.app.domain.model.FilePayload
import com.sotto.app.domain.model.GroupEvent
import com.sotto.app.domain.model.GroupKeyState
import com.sotto.app.domain.model.GroupStatus
import com.sotto.app.domain.model.MessageState
import com.sotto.app.group.GroupIds
import com.sotto.app.group.GroupPacket
import com.sotto.app.group.GroupPacketCodec
import com.sotto.app.group.GroupRoster
import com.sotto.app.group.RosterMember
import com.sotto.app.group.RosterRules
import com.sotto.app.group.TestAccount
import com.sotto.app.worker.FileTransfers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Group chats end to end (ADR-32): several phones, each with its own database and keys, passing
 * real signed rosters and packets through a fake relay. The pairwise encryption under them is
 * the 1:1 channel's ([GroupTransport]); here a copy's "ciphertext" is its packet, so what the
 * relay holds can be checked.
 */
class GroupRepositoryTest {

    private val relay = FakeRelay()
    private val accounts = listOf("astrid", "bjorn", "cecilia", "dagny").associateWith { TestAccount(it) }
    private val phones = LinkedHashMap<String, Phone>()

    inner class Phone(val account: TestAccount) {
        val me = account.userId
        val groupDao = FakeGroupDao()
        val messageDao = FakeMessageDao().also { groupDao.messages = it }
        val contactDao = FakeContactDao()
        val conversationDao = FakeConversationDao()
        val blocked = FakeBlockedUserDao()
        val notified = mutableListOf<Pair<String, String>>()
        val invitations = mutableListOf<String>()
        var outboxScheduled = 0
        /** Seals per recipient: a retry must not seal a copy again. */
        val seals = mutableMapOf<String, Int>()
        /** Sealing for these finds an endorsed key change (as MessageRepositoryImpl.recordRemoteRotation reports it). */
        val rotatedOnSeal = mutableSetOf<String>()
        val uploads = mutableListOf<List<String>>()

        val transport = object : GroupTransport {
            override suspend fun seal(peerId: String, plaintext: String): GroupTransport.Sealed {
                // As the real channel: only to someone whose keys are pinned on this phone.
                contactDao.getByUserId(peerId) ?: throw SecurityException("No keys pinned for $peerId")
                seals[peerId] = (seals[peerId] ?: 0) + 1
                if (peerId in rotatedOnSeal) repo.keysRotated(peerId)
                return GroupTransport.Sealed(plaintext.toByteArray(), byteArrayOf(seals[peerId]!!.toByte()), "sig", "pq")
            }

            override suspend fun post(peerId: String, sealed: GroupTransport.Sealed, clientMessageId: String) =
                relay.post(me, peerId, clientMessageId, String(sealed.ciphertext))

            override suspend fun upload(entity: MessageEntity, recipients: List<String>): MessageEntity {
                uploads += recipients
                val done = entity.copy(attachmentId = "a".repeat(32), mediaState = "sent")
                messageDao.insert(done)
                return done
            }
        }

        val env = object : GroupEnvironment {
            override fun me() = this@Phone.me
            override fun myFingerprint() = account.fingerprint
            override suspend fun myName() = me.replaceFirstChar { it.uppercase() }
            override fun now() = relay.clock
            override fun preview(entity: MessageEntity) = if (entity.mediaKind != null) "[${entity.mediaKind}] ${entity.plaintext}".trim() else entity.plaintext
            override fun reactionText(emoji: String, original: String) = "Reacted $emoji to \"$original\""
            override fun unknownPayloadNote() = "Needs a newer Sotto"
            override suspend fun notifyMessage(group: GroupEntity, senderId: String, senderName: String, text: String, isReaction: Boolean, names: Map<String, String>) {
                notified += group.title to "$senderName: $text"
            }
            override suspend fun notifyInvitation(group: GroupEntity, inviterName: String) { invitations += group.title }
            override fun cancelNotification(groupId: String) = Unit
            override fun scheduleOutbox() { outboxScheduled++ }
        }

        val fileDrop = mockk<FileDrop>(relaxed = true)
        val transfers = mockk<FileTransfers>(relaxed = true)

        val repo: GroupRepositoryImpl = GroupRepositoryImpl(
            groupDao = groupDao,
            messageDao = messageDao,
            contactDao = contactDao,
            conversationDao = conversationDao,
            blockedUserDao = blocked,
            transport = { transport },
            memberKeys = GroupKeys(contactDao, relay.api(), mockk<ContactKeyVerifier>(relaxed = true), relaxedCrypto()),
            signatures = account.signatures,
            env = env,
            mediaStore = mockk<MediaStore>(relaxed = true),
            fileDrop = fileDrop,
            transfers = transfers,
            receipts = { mockk(relaxed = true) },
        )

        /** Makes [other] a contact of this phone (added and accepted). */
        fun knows(other: String, inPerson: Boolean = true) {
            contactDao.rows[other] = contactOf(accounts.getValue(other), inPerson)
            conversationDao.rows[other] = ConversationEntity("c-$other", other, other, null, null, 0, true, isApproved = true)
        }

        /** Takes everything the relay holds for this phone, as the fetch would. */
        suspend fun fetch(): Int {
            val queue = relay.queues.remove(me).orEmpty()
            for (e in queue) receive(e)
            return queue.size
        }

        suspend fun receive(e: FakeRelay.Envelope) {
            val sender = accounts.getValue(e.sender)
            val pinned = contactDao.getByUserId(e.sender)
            // As MessageRepositoryImpl: the envelope was verified with the pinned keys, or (for
            // someone not pinned) with the relay's, which are then handed along.
            val relayKeys = relay.directory[e.sender] ?: sender
            val newKeys = if (pinned == null) {
                VerifiedKeys(e.sender, relayKeys.identityKey, relayKeys.edPublic, relayKeys.kemKey, relayKeys.mlDsaPublic)
            } else {
                null
            }
            repo.receive(
                GroupRepositoryImpl.Inbound(e.relayId, e.sender, e.plaintext, e.timestamp, pinned?.keyFingerprint ?: newKeys!!.fingerprintHex, newKeys),
            )
        }

        suspend fun group(id: String) = groupDao.get(id)
        suspend fun status(id: String) = groupDao.get(id)?.let { GroupStatus.fromWire(it.status) }
        fun texts(groupId: String) = messageDao.messagesIn(groupId).map { it.senderId to it.plaintext }
        fun events(groupId: String) = messageDao.inGroup(groupId).filter { it.state == "GROUP_EVENT" }.map { GroupEvent.decode(it.plaintext)!!.type }
    }

    private fun phone(name: String) = phones.getOrPut(name) { Phone(accounts.getValue(name)) }
    private val astrid get() = phone("astrid")
    private val bjorn get() = phone("bjorn")
    private val cecilia get() = phone("cecilia")
    private val dagny get() = phone("dagny")

    private suspend fun fetchAll() {
        repeat(6) { if (phones.values.sumOf { it.fetch() } == 0) return }
    }

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        accounts.values.forEach { relay.directory[it.userId] = it }
        // Astrid knows everyone; Bjorn and Cecilia know Astrid only (not each other).
        listOf("bjorn", "cecilia", "dagny").forEach { astrid.knows(it); phone(it).knows("astrid") }
    }

    @After
    fun tearDown() = unmockkAll()

    /** Astrid's group with Bjorn and Cecilia, both joined. */
    private suspend fun fika(): String {
        val id = astrid.repo.createGroup("Fika", listOf("bjorn", "cecilia"))
        fetchAll()
        bjorn.repo.acceptInvitation(id)
        cecilia.repo.acceptInvitation(id)
        fetchAll()
        astrid.repo.flushOutbox() // the rosters the acceptances led to go in the background
        fetchAll()
        return id
    }

    // ── Invitations ───────────────────────────────────────────────────────

    @Test
    fun `an invitation shows as pending until accepted, and only then does content arrive`() = runTest {
        val id = astrid.repo.createGroup("Fika", listOf("bjorn", "cecilia"))
        assertEquals(GroupStatus.ACTIVE, astrid.status(id))
        fetchAll()
        assertEquals(GroupStatus.INVITED, bjorn.status(id))
        assertEquals(listOf("Fika"), bjorn.invitations)
        // Nothing goes to an invitee: Astrid's message has no copy for them.
        astrid.repo.sendText(id, "Kaffe kl 15?")
        fetchAll()
        assertTrue(bjorn.texts(id).isEmpty())
        // No 1:1 chat was opened by any of it.
        assertNull(bjorn.conversationDao.getByContactId("cecilia"))

        bjorn.repo.acceptInvitation(id)
        assertEquals(GroupStatus.JOINING, bjorn.status(id))
        fetchAll()
        astrid.repo.flushOutbox()
        fetchAll()
        assertEquals(GroupStatus.ACTIVE, bjorn.status(id))
        astrid.repo.sendText(id, "Välkommen!")
        fetchAll()
        assertEquals(listOf("astrid" to "Välkommen!"), bjorn.texts(id))
    }

    @Test
    fun `declining takes the invitee out of the roster, and nothing more reaches them`() = runTest {
        val id = astrid.repo.createGroup("Fika", listOf("bjorn", "cecilia"))
        fetchAll()
        cecilia.repo.rejectInvitation(id)
        fetchAll()
        astrid.repo.flushOutbox()
        fetchAll()
        assertEquals(GroupStatus.REJECTED, cecilia.status(id))
        assertNull(astrid.groupDao.getMember(id, "cecilia"))
        assertTrue(GroupEvent.DECLINED in astrid.events(id))
        assertEquals(0, relay.queues["cecilia"].orEmpty().size)
    }

    @Test
    fun `an invitation from someone who isn't an accepted contact is dropped`() = runTest {
        // Dagny doesn't know Bjorn; she invites him anyway.
        dagny.knows("bjorn")
        val id = dagny.repo.createGroup("Spam", listOf("bjorn"))
        bjorn.fetch()
        assertNull(bjorn.group(id))
        assertTrue(bjorn.invitations.isEmpty())
        // Nor from a contact Bjorn has blocked.
        bjorn.knows("dagny")
        bjorn.blocked.blocked += "dagny"
        val again = dagny.repo.createGroup("Spam 2", listOf("bjorn"))
        bjorn.fetch()
        assertNull(bjorn.group(again))
    }

    @Test
    fun `only contacts can be invited, and a blocked one can't`() = runTest {
        astrid.blocked.blocked += "dagny"
        assertThrows { astrid.repo.createGroup("Fika", listOf("dagny")) }
        assertThrows { astrid.repo.createGroup("Fika", listOf("eskil")) }
        assertThrows { astrid.repo.createGroup(" ", listOf("bjorn")) }
    }

    // ── Vouched members ───────────────────────────────────────────────────

    @Test
    fun `members who aren't contacts get the keys the admin vouched for, never a contact or chat`() = runTest {
        val id = fika()
        val pin = bjorn.contactDao.getByUserId("cecilia")
        assertNotNull(pin)
        assertTrue(pin!!.groupOnly)
        assertEquals(accounts.getValue("cecilia").fingerprint, pin.keyFingerprint)
        assertEquals(GroupKeyState.OK.wire, bjorn.groupDao.getMember(id, "cecilia")!!.keyState)
        assertNull(bjorn.conversationDao.getByContactId("cecilia"))

        cecilia.repo.sendText(id, "Hej Björn")
        fetchAll()
        assertEquals(listOf("cecilia" to "Hej Björn"), bjorn.texts(id))
        // How the admin verified her is on the roster.
        assertEquals(RosterMember.TRUST_IN_PERSON, bjorn.groupDao.getMember(id, "cecilia")!!.vouchedTrust)
    }

    @Test
    fun `keys from the relay that aren't the vouched ones are refused both ways`() = runTest {
        // The relay hands out other keys for Cecilia, to anyone who asks.
        relay.directory["cecilia"] = TestAccount("cecilia")
        val id = fika()
        assertEquals(GroupKeyState.MISMATCH.wire, bjorn.groupDao.getMember(id, "cecilia")!!.keyState)
        assertNull(bjorn.contactDao.getByUserId("cecilia"))
        assertTrue(GroupEvent.KEYS_MISMATCH in bjorn.events(id))
        // Bjorn's message: Astrid gets it, Cecilia's copy fails ("keys"), shown as partial.
        bjorn.repo.sendText(id, "Hej")
        val row = bjorn.messageDao.messagesIn(id).single()
        assertEquals(MessageState.FAILED.name, row.state)
        val copies = bjorn.groupDao.getCopiesForMessage(row.id).associateBy { it.recipientId }
        assertEquals("sent", copies["astrid"]!!.state)
        assertEquals(GroupRepositoryImpl.ERROR_KEYS, copies["cecilia"]!!.lastError)
    }

    @Test
    fun `an envelope from a member must have been signed with the vouched keys`() = runTest {
        val id = fika()
        // Someone with other keys under Cecilia's id (a relay lying to a phone that had none).
        val forger = TestAccount("cecilia")
        val packet = GroupPacket(
            kind = GroupPacket.KIND_MSG, groupId = id, id = GroupIds.newId(), sender = "cecilia", to = "dagny",
            epoch = astrid.group(id)!!.epoch, timestamp = relay.clock, body = "fake",
        )
        // Dagny isn't a member; try on Bjorn with a pin removed.
        bjorn.contactDao.delete("cecilia")
        bjorn.repo.receive(
            GroupRepositoryImpl.Inbound(
                "r1", "cecilia", GroupPacketCodec.encode(packet.copy(to = "bjorn")), relay.clock, forger.fingerprint,
                VerifiedKeys("cecilia", forger.identityKey, forger.edPublic, forger.kemKey, forger.mlDsaPublic),
            ),
        )
        assertTrue(bjorn.texts(id).isEmpty())
        assertNull(bjorn.contactDao.getByUserId("cecilia"))
    }

    // ── Sending, partial failure, retry ───────────────────────────────────

    @Test
    fun `each member's copy has its own client id, and the relay sees no group data`() = runTest {
        val id = fika()
        relay.posted.clear()
        astrid.repo.sendText(id, "Fika?")
        val copies = relay.posted.filter { it.sender == "astrid" }
        assertEquals(setOf("bjorn", "cecilia"), copies.map { it.to }.toSet())
        assertEquals(2, copies.map { it.relayId }.toSet().size)
        // What travels is an ordinary envelope per recipient; inside it, the packet names them.
        for (c in copies) {
            val p = (GroupPacketCodec.decode(c.plaintext, relay.clock) as GroupPacketCodec.Decoded.Ok).packet
            assertEquals(c.to, p.to)
            assertEquals("Fika?", p.body)
        }
        // Both copies carry the same message id: one message on every phone.
        val ids = copies.map { (GroupPacketCodec.decode(it.plaintext, relay.clock) as GroupPacketCodec.Decoded.Ok).packet.id }.toSet()
        assertEquals(1, ids.size)
        fetchAll()
        val row = GroupIds.rowId(id, "astrid", ids.single())
        assertNotNull(bjorn.messageDao.getById(row))
        assertNotNull(cecilia.messageDao.getById(row))
        assertNotNull(astrid.messageDao.getById(row))
    }

    @Test
    fun `a partial send says who didn't get it, and a retry sends only theirs, sealed once`() = runTest {
        val id = fika()
        relay.failFor += "cecilia"
        astrid.repo.sendText(id, "Fika?")
        val row = astrid.messageDao.messagesIn(id).single()
        assertEquals(MessageState.FAILED.name, row.state)
        val copies = astrid.groupDao.getCopiesForMessage(row.id).associateBy { it.recipientId }
        assertEquals("sent", copies["bjorn"]!!.state)
        assertEquals("failed", copies["cecilia"]!!.state)
        val copyStates = astrid.repo.observeCopies(row.id).first().associate { it.recipientId to it.state.name }
        assertEquals(mapOf("bjorn" to "SENT", "cecilia" to "FAILED"), copyStates)
        // Sealed before it failed: those bytes are kept for the retry.
        val sealedBefore = copies["cecilia"]!!.ciphertext!!.copyOf()

        relay.failFor.clear()
        val sealsBefore = astrid.seals.toMap()
        relay.posted.clear()
        astrid.repo.retry(row.id)
        assertEquals(MessageState.SENT.name, astrid.messageDao.getById(row.id)!!.state)
        assertEquals(listOf("cecilia"), relay.posted.map { it.to })
        assertEquals(String(sealedBefore), relay.posted.single().plaintext)
        assertEquals(sealsBefore, astrid.seals.toMap()) // nothing sealed again
        fetchAll()
        // Once for each, however often it was tried.
        assertEquals(1, bjorn.texts(id).size)
        assertEquals(1, cecilia.texts(id).size)
    }

    @Test
    fun `a copy the relay already took is kept once when it comes again`() = runTest {
        val id = fika()
        astrid.repo.sendText(id, "Hej")
        val envelope = relay.queues["bjorn"]!!.single()
        bjorn.fetch()
        // The relay delivers the same envelope again, and a resend under the same id too.
        bjorn.receive(envelope)
        bjorn.receive(FakeRelay.Envelope("other-relay-id", envelope.sender, envelope.to, envelope.plaintext, envelope.timestamp))
        assertEquals(1, bjorn.texts(id).size)
        assertEquals(1, bjorn.group(id)!!.unreadCount)
    }

    @Test
    fun `copies sealed before a process death go out as they were`() = runTest {
        val id = fika()
        relay.failFor += "bjorn"
        relay.failFor += "cecilia"
        astrid.repo.sendText(id, "Hej")
        val row = astrid.messageDao.messagesIn(id).single()
        // As if the app died after sealing: the copies wait sealed, the message shows sending.
        astrid.groupDao.outbox.replaceAll { _, c -> if (c.messageId == row.id) c.copy(state = "sealed") else c }
        astrid.messageDao.updateState(row.id, MessageState.SENDING.name)
        relay.failFor.clear()
        val seals = astrid.seals.toMap()
        astrid.repo.resumeInterrupted()
        assertEquals(seals, astrid.seals.toMap())
        assertEquals(MessageState.SENT.name, astrid.messageDao.getById(row.id)!!.state)
        fetchAll()
        assertEquals(1, bjorn.texts(id).size)
    }

    // ── Membership and epochs ─────────────────────────────────────────────

    @Test
    fun `a removed member gets no later messages, keeps what they had, and their own are refused`() = runTest {
        val id = fika()
        astrid.repo.sendText(id, "Före")
        fetchAll()
        // Cecilia writes with the roster she has, while Astrid removes her.
        cecilia.repo.sendText(id, "Hej")
        val ceciliasCopyToBjorn = relay.queues["bjorn"]!!.single()
        relay.queues.remove("bjorn")
        astrid.repo.remove(id, "cecilia")
        bjorn.fetch() // the roster without Cecilia
        bjorn.receive(ceciliasCopyToBjorn)
        assertEquals(listOf("astrid" to "Före"), bjorn.texts(id))

        assertTrue(GroupEvent.REMOVED in bjorn.events(id))
        assertFalse(GroupEvent.LEFT in bjorn.events(id))
        cecilia.fetch()
        assertEquals(GroupStatus.REMOVED, cecilia.status(id))
        assertTrue(GroupEvent.YOU_REMOVED in cecilia.events(id))
        relay.posted.clear()
        astrid.repo.sendText(id, "Efter")
        assertEquals(listOf("bjorn"), relay.posted.map { it.to })
        fetchAll()
        // She keeps what she had (and her own last message), and gets nothing later.
        assertEquals(listOf("astrid" to "Före", "cecilia" to "Hej"), cecilia.texts(id))
        // And can't write any more.
        assertThrows { cecilia.repo.sendText(id, "Hallå?") }
    }

    @Test
    fun `content from an older epoch is taken only from members still in`() = runTest {
        val id = fika()
        bjorn.repo.sendText(id, "Gammal")
        val old = relay.queues["cecilia"]!!.single()
        relay.queues.remove("cecilia")
        astrid.repo.rename(id, "Fika 2")
        cecilia.fetch()
        cecilia.receive(old)
        assertEquals(listOf("bjorn" to "Gammal"), cecilia.texts(id))
    }

    @Test
    fun `content from a newer epoch waits for its roster`() = runTest {
        val id = astrid.repo.createGroup("Fika", listOf("bjorn", "cecilia"))
        fetchAll()
        bjorn.repo.acceptInvitation(id)
        fetchAll()
        astrid.repo.flushOutbox()
        fetchAll()
        cecilia.repo.acceptInvitation(id)
        astrid.fetch()
        astrid.repo.flushOutbox()
        // Cecilia (now in) writes before Bjorn has the roster that has her.
        cecilia.fetch()
        cecilia.repo.sendText(id, "Hej allihop")
        val rosterForBjorn = relay.queues["bjorn"]!!.filter { it.sender == "astrid" }
        val fromCecilia = relay.queues["bjorn"]!!.single { it.sender == "cecilia" }
        relay.queues.remove("bjorn")
        bjorn.receive(fromCecilia)
        assertTrue(bjorn.texts(id).isEmpty())
        assertEquals(1, bjorn.groupDao.held.size)
        rosterForBjorn.forEach { bjorn.receive(it) }
        assertEquals(listOf("cecilia" to "Hej allihop"), bjorn.texts(id))
        assertTrue(bjorn.groupDao.held.isEmpty())
    }

    @Test
    fun `only the admin changes the group, and a forged roster is refused`() = runTest {
        val id = fika()
        assertThrows { bjorn.repo.rename(id, "Bjorns grupp") }
        // Bjorn signs a roster himself and sends it to Cecilia.
        val stored = Json.decodeFromString(GroupRoster.serializer(), cecilia.group(id)!!.roster)
        val forged = RosterRules.sign(
            stored.copy(epoch = stored.epoch + 1, issuer = "bjorn", admin = "bjorn", title = "Kuppen"),
            accounts.getValue("bjorn").signatures,
        )
        val packet = GroupPacket(kind = GroupPacket.KIND_ROSTER, groupId = id, id = GroupIds.newId(), sender = "bjorn", to = "cecilia", epoch = forged.epoch, timestamp = relay.clock, roster = forged)
        cecilia.receive(FakeRelay.Envelope("x1", "bjorn", "cecilia", GroupPacketCodec.encode(packet), relay.clock))
        assertEquals("Fika", cecilia.group(id)!!.title)
        assertEquals("astrid", cecilia.group(id)!!.adminId)
    }

    @Test
    fun `a packet whose sender or recipient isn't its envelope's is dropped`() = runTest {
        val id = fika()
        val packet = GroupPacket(
            kind = GroupPacket.KIND_MSG, groupId = id, id = GroupIds.newId(), sender = "astrid", to = "cecilia",
            epoch = bjorn.group(id)!!.epoch, timestamp = relay.clock, body = "Från Astrid?",
        )
        // Bjorn forwards a packet naming Astrid as its sender.
        cecilia.receive(FakeRelay.Envelope("x2", "bjorn", "cecilia", GroupPacketCodec.encode(packet.copy(sender = "astrid")), relay.clock))
        // Astrid's packet for Cecilia, delivered to Bjorn.
        bjorn.receive(FakeRelay.Envelope("x3", "astrid", "bjorn", GroupPacketCodec.encode(packet), relay.clock))
        assertTrue(cecilia.texts(id).isEmpty())
        assertTrue(bjorn.texts(id).isEmpty())
    }

    @Test
    fun `malformed and oversized packets are dropped without harm`() = runTest {
        val id = fika()
        for (bad in listOf(GroupPacketCodec.PREFIX + "{", GroupPacketCodec.PREFIX + "x".repeat(GroupPacketCodec.MAX_CHARS + 1), GroupPacketCodec.PREFIX + "{\"v\":9}")) {
            bjorn.receive(FakeRelay.Envelope(GroupIds.newId(), "astrid", "bjorn", bad, relay.clock))
        }
        assertTrue(bjorn.texts(id).isEmpty())
        assertEquals(GroupStatus.ACTIVE, bjorn.status(id))
    }

    @Test
    fun `the admin hands over, and the new admin's changes are taken`() = runTest {
        val id = fika()
        astrid.repo.makeAdmin(id, "bjorn")
        fetchAll()
        assertEquals("bjorn", cecilia.group(id)!!.adminId)
        assertThrows { astrid.repo.rename(id, "Astrids") }
        bjorn.repo.rename(id, "Björns fika")
        fetchAll()
        assertEquals("Björns fika", cecilia.group(id)!!.title)
        assertEquals("Björns fika", astrid.group(id)!!.title)
        assertTrue(GroupEvent.ADMIN in cecilia.events(id))
    }

    @Test
    fun `a phone that missed a handover to someone it never knew follows it with the vouched keys`() = runTest {
        val id = fika()
        // Cecilia is away: nothing reaches her for a while.
        fun holdCecilia() = relay.queues.remove("cecilia")
        astrid.repo.invite(id, listOf("dagny"))
        holdCecilia(); fetchAll()
        dagny.repo.acceptInvitation(id)
        holdCecilia(); fetchAll()
        astrid.repo.flushOutbox()
        holdCecilia(); fetchAll()
        astrid.repo.makeAdmin(id, "dagny")
        holdCecilia(); fetchAll()
        dagny.repo.rename(id, "Dagnys fika")
        // Only Dagny's latest roster reaches Cecilia; the ones before it were lost.
        val latest = relay.queues.remove("cecilia")!!.last { it.sender == "dagny" }
        assertNull(cecilia.contactDao.getByUserId("dagny"))
        cecilia.receive(latest)
        assertEquals("Dagnys fika", cecilia.group(id)!!.title)
        assertEquals("dagny", cecilia.group(id)!!.adminId)
        // Dagny's keys were taken from the relay only because Astrid's handover vouched for them.
        val pin = cecilia.contactDao.getByUserId("dagny")!!
        assertTrue(pin.groupOnly)
        assertEquals(accounts.getValue("dagny").fingerprint, pin.keyFingerprint)
    }

    @Test
    fun `a relay serving other keys for an unknown new admin can't take the group over`() = runTest {
        val id = fika()
        fun holdCecilia() = relay.queues.remove("cecilia")
        astrid.repo.invite(id, listOf("dagny"))
        holdCecilia(); fetchAll()
        dagny.repo.acceptInvitation(id)
        holdCecilia(); fetchAll()
        astrid.repo.flushOutbox()
        holdCecilia(); fetchAll()
        astrid.repo.makeAdmin(id, "dagny")
        holdCecilia(); fetchAll()
        dagny.repo.rename(id, "Dagnys fika")
        val latest = relay.queues.remove("cecilia")!!.last { it.sender == "dagny" }
        // The relay now hands out someone else's keys for Dagny.
        relay.directory["dagny"] = TestAccount("dagny")
        cecilia.receive(latest)
        assertEquals("Fika", cecilia.group(id)!!.title)
        assertEquals("astrid", cecilia.group(id)!!.adminId)
        assertNull(cecilia.contactDao.getByUserId("dagny"))
    }

    @Test
    fun `a member who leaves is taken out by the admin, and the admin who leaves hands over first`() = runTest {
        val id = fika()
        cecilia.repo.leave(id)
        assertEquals(GroupStatus.LEFT, cecilia.status(id))
        fetchAll()
        astrid.repo.flushOutbox()
        fetchAll()
        assertNull(bjorn.groupDao.getMember(id, "cecilia"))
        // Noted once, on the admin's phone and on a member's.
        assertEquals(1, astrid.events(id).count { it == GroupEvent.LEFT })
        assertEquals(1, bjorn.events(id).count { it == GroupEvent.LEFT })
        assertFalse(GroupEvent.REMOVED in astrid.events(id))
        relay.posted.clear()
        astrid.repo.sendText(id, "Nu är vi två")
        assertEquals(listOf("bjorn"), relay.posted.map { it.to })

        fetchAll()
        astrid.repo.leave(id)
        fetchAll()
        bjorn.repo.flushOutbox()
        fetchAll()
        assertEquals("bjorn", bjorn.group(id)!!.adminId)
        assertNull(bjorn.groupDao.getMember(id, "astrid"))
    }

    @Test
    fun `when the admin's account is gone the group is frozen`() = runTest {
        val id = fika()
        bjorn.repo.onAccountDeleted("astrid")
        assertTrue(bjorn.group(id)!!.frozen)
        assertEquals(1, bjorn.events(id).count { it == GroupEvent.LEFT })
        assertTrue(GroupEvent.FROZEN in bjorn.events(id))
        // Messages still go between those left.
        relay.posted.clear()
        bjorn.repo.sendText(id, "Hon är borta")
        assertEquals(listOf("cecilia"), relay.posted.map { it.to })
    }

    @Test
    fun `the admin deleting the group ends it everywhere`() = runTest {
        val id = fika()
        astrid.repo.deleteGroup(id)
        fetchAll()
        assertEquals(GroupStatus.ENDED, bjorn.status(id))
        assertThrows { bjorn.repo.sendText(id, "?") }
        bjorn.repo.deleteLocally(id)
        assertTrue(bjorn.messageDao.inGroup(id).isEmpty())
    }

    // ── Found in review ───────────────────────────────────────────────────

    @Test
    fun `a key change noticed while sending a roster doesn't lock the group`() = runTest {
        val id = fika()
        astrid.rotatedOnSeal += "bjorn"
        kotlinx.coroutines.withTimeout(5_000) { astrid.repo.rename(id, "Fika 2") }
        kotlinx.coroutines.withTimeout(5_000) { astrid.repo.sendText(id, "Still works") }
        assertEquals("Fika 2", astrid.group(id)!!.title)
    }

    @Test
    fun `messages held while joining survive an older roster and come in with the one that adds us`() = runTest {
        val id = astrid.repo.createGroup("Fika", listOf("bjorn", "cecilia"))
        fetchAll()
        cecilia.repo.acceptInvitation(id)
        astrid.fetch(); astrid.repo.flushOutbox() // roster 2: Cecilia in, Bjorn invited
        bjorn.repo.acceptInvitation(id)
        astrid.fetch(); astrid.repo.flushOutbox() // roster 3: Bjorn in
        cecilia.fetch()
        cecilia.repo.sendText(id, "Hej Björn")
        val queue = relay.queues.remove("bjorn")!!
        val message = queue.single { it.sender == "cecilia" }
        val rosters = queue.filter { it.sender == "astrid" }
        bjorn.receive(message)
        assertEquals(1, bjorn.groupDao.held.size)
        // An older roster (he's still only invited in it): the message keeps waiting.
        rosters.filter { "\"e\":2" in it.plaintext }.forEach { bjorn.receive(it) }
        assertEquals(1, bjorn.groupDao.held.size)
        rosters.forEach { bjorn.receive(it) }
        assertEquals(listOf("cecilia" to "Hej Björn"), bjorn.texts(id))
        // At the relay's time for it, and with Cecilia's vouched keys pinned.
        assertEquals(message.timestamp, bjorn.messageDao.messagesIn(id).single().timestamp)
        assertEquals(accounts.getValue("cecilia").fingerprint, bjorn.contactDao.getByUserId("cecilia")!!.keyFingerprint)
        assertEquals(GroupKeyState.OK.wire, bjorn.groupDao.getMember(id, "cecilia")!!.keyState)
    }

    @Test
    fun `a roster held for keys the relay couldn't give is taken once it can`() = runTest {
        val id = fika()
        fun holdCecilia() = relay.queues.remove("cecilia")
        astrid.repo.invite(id, listOf("dagny"))
        holdCecilia(); fetchAll()
        dagny.repo.acceptInvitation(id)
        holdCecilia(); fetchAll()
        astrid.repo.flushOutbox()
        holdCecilia(); fetchAll()
        astrid.repo.makeAdmin(id, "dagny")
        holdCecilia(); fetchAll()
        dagny.repo.rename(id, "Dagnys fika")
        val latest = relay.queues.remove("cecilia")!!.last { it.sender == "dagny" }
        relay.failBundles += "dagny"
        cecilia.receive(latest)
        assertEquals("Fika", cecilia.group(id)!!.title)
        assertEquals(1, cecilia.groupDao.held.size)
        relay.failBundles.clear()
        cecilia.repo.retryHeld()
        assertEquals("Dagnys fika", cecilia.group(id)!!.title)
        assertTrue(cecilia.groupDao.held.isEmpty())
    }

    @Test
    fun `an admin who leaves is out of the group even if the new admin hears the leave first`() = runTest {
        val id = fika()
        astrid.repo.leave(id)
        val queue = relay.queues.remove("bjorn")!!
        // The leave first, the handover after.
        queue.sortedByDescending { "\"leave\"" in it.plaintext }.forEach { bjorn.receive(it) }
        assertEquals("bjorn", bjorn.group(id)!!.adminId)
        assertNull(bjorn.groupDao.getMember(id, "astrid"))
        cecilia.fetch()
        assertNull(cecilia.groupDao.getMember(id, "astrid"))
        assertEquals(GroupStatus.LEFT, astrid.status(id))
    }

    @Test
    fun `a file sent while nobody else has joined yet is done, not stuck`() = runTest {
        val id = astrid.repo.createGroup("Fika", listOf("bjorn"))
        coEvery { astrid.fileDrop.encrypt(any(), any(), any()) } returns EncryptedFile(ByteArray(32), "a.pdf", "application/pdf", 10, 20)
        astrid.repo.sendFile(id, mockk(relaxed = true), "", null)
        val row = astrid.messageDao.messagesIn(id).single()
        astrid.repo.finishSend(row)
        assertEquals(MessageState.SENT.name, astrid.messageDao.getById(row.id)!!.state)
        assertTrue(astrid.uploads.isEmpty())
    }

    // ── Replies, reactions, receipts, timer, notifications ────────────────

    @Test
    fun `a reply names the original by its group id, the same on every phone`() = runTest {
        val id = fika()
        cecilia.repo.sendText(id, "Kanelbullar?")
        fetchAll()
        val original = bjorn.messageDao.messagesIn(id).single()
        bjorn.repo.sendText(id, "Ja tack!", replyTo = original.toMessage())
        fetchAll()
        val atCecilia = cecilia.messageDao.messagesIn(id).single { it.senderId == "bjorn" }
        assertEquals(original.id, atCecilia.replyToId)
        assertTrue(atCecilia.replyToOwn) // her own message
        val atAstrid = astrid.messageDao.messagesIn(id).single { it.senderId == "bjorn" }
        assertEquals(original.id, atAstrid.replyToId)
        assertFalse(atAstrid.replyToOwn)
        assertEquals("Kanelbullar?", atAstrid.replyQuote)
    }

    @Test
    fun `reactions are per member, and one taken back leaves the others`() = runTest {
        val id = fika()
        astrid.repo.sendText(id, "Fika kl 15")
        fetchAll()
        val atBjorn = bjorn.messageDao.messagesIn(id).single()
        bjorn.repo.react(id, atBjorn.toMessage(), "❤️")
        val atCecilia = cecilia.messageDao.messagesIn(id).single()
        cecilia.repo.react(id, atCecilia.toMessage(), "👍")
        fetchAll()
        val row = atBjorn.id
        assertEquals(mapOf("bjorn" to "❤️", "cecilia" to "👍"), astrid.groupDao.getReactions(row).associate { it.reactorId to it.emoji })
        assertEquals(mapOf("bjorn" to "❤️", "cecilia" to "👍"), cecilia.groupDao.getReactions(row).associate { it.reactorId to it.emoji })
        // Astrid is told (her message), as in a 1:1 chat.
        assertTrue(astrid.notified.any { it.second.contains("Reacted ❤️") })
        bjorn.repo.react(id, atBjorn.toMessage(), null)
        fetchAll()
        assertEquals(mapOf("cecilia" to "👍"), astrid.groupDao.getReactions(row).associate { it.reactorId to it.emoji })
    }

    @Test
    fun `receipts count per member, and one member's never marks the message for everyone`() = runTest {
        val id = fika()
        astrid.repo.sendText(id, "Hej")
        val row = astrid.messageDao.messagesIn(id).single()
        val copies = astrid.groupDao.getCopiesForMessage(row.id).associateBy { it.recipientId }
        astrid.repo.onDelivered("bjorn", listOf(copies["bjorn"]!!.relayId))
        assertEquals(MessageState.SENT.name, astrid.messageDao.getById(row.id)!!.state)
        // A receipt from Bjorn naming Cecilia's copy does nothing.
        astrid.repo.onRead("bjorn", listOf(copies["cecilia"]!!.relayId))
        assertEquals("sent", astrid.groupDao.getCopy(copies["cecilia"]!!.id)!!.state)
        astrid.repo.onDelivered("cecilia", listOf(copies["cecilia"]!!.relayId))
        assertEquals(MessageState.DELIVERED.name, astrid.messageDao.getById(row.id)!!.state)
        astrid.repo.onRead("bjorn", listOf(copies["bjorn"]!!.relayId))
        assertEquals(MessageState.DELIVERED.name, astrid.messageDao.getById(row.id)!!.state)
        astrid.repo.onRead("cecilia", listOf(copies["cecilia"]!!.relayId))
        assertEquals(MessageState.SEEN.name, astrid.messageDao.getById(row.id)!!.state)
        // A late delivery receipt doesn't undo a read.
        astrid.repo.onDelivered("cecilia", listOf(copies["cecilia"]!!.relayId))
        assertEquals("read", astrid.groupDao.getCopy(copies["cecilia"]!!.id)!!.state)
    }

    @Test
    fun `received group messages keep their copy's relay id for read receipts`() = runTest {
        val id = fika()
        astrid.repo.sendText(id, "Hej")
        val envelope = relay.queues["bjorn"]!!.single()
        fetchAll()
        assertEquals(envelope.relayId, bjorn.messageDao.messagesIn(id).single().relayId)
        assertEquals(astrid.groupDao.outbox.values.single { it.recipientId == "bjorn" && it.messageId != null }.relayId, envelope.relayId)
    }

    @Test
    fun `the admin's timer makes every phone's messages disappear`() = runTest {
        val id = fika()
        astrid.repo.setTimer(id, 3_600_000)
        fetchAll()
        assertEquals(3_600_000, bjorn.group(id)!!.timerMs)
        // The admin's own change reads "You ..." (no name); others see who.
        val ownEvent = astrid.messageDao.inGroup(id).map { GroupEvent.decode(it.plaintext) }.last { it?.type == GroupEvent.TIMER }!!
        assertEquals(listOf(""), ownEvent.names)
        val theirs = bjorn.messageDao.inGroup(id).map { GroupEvent.decode(it.plaintext) }.last { it?.type == GroupEvent.TIMER }!!
        assertEquals(listOf("Astrid"), theirs.names)
        assertThrows { bjorn.repo.setTimer(id, 0) }
        cecilia.repo.sendText(id, "Snart borta")
        fetchAll()
        val atBjorn = bjorn.messageDao.messagesIn(id).single()
        val atCecilia = cecilia.messageDao.messagesIn(id).single()
        assertNotNull(atBjorn.expiresAt)
        assertNotNull(atCecilia.expiresAt)
        assertTrue(atBjorn.expiresAt!! - relay.clock <= 3_600_000)
        relay.clock += 3_600_001
        bjorn.messageDao.deleteExpired(relay.clock)
        assertTrue(bjorn.texts(id).isEmpty())
        // Its text doesn't stay behind in the chat list.
        bjorn.repo.prune()
        assertNull(bjorn.group(id)!!.lastMessagePreview)
    }

    @Test
    fun `a message notifies with the group and its sender, and counts as unread unless the chat is open`() = runTest {
        val id = fika()
        astrid.repo.sendText(id, "Ett")
        fetchAll()
        assertEquals(listOf("Fika" to "Astrid: Ett"), bjorn.notified)
        assertEquals(1, bjorn.group(id)!!.unreadCount)
        assertEquals("Astrid: Ett", bjorn.group(id)!!.lastMessagePreview)
        bjorn.repo.setActiveGroup(id)
        astrid.repo.sendText(id, "Två")
        fetchAll()
        assertEquals(1, bjorn.notified.size)
        assertEquals(1, bjorn.group(id)!!.unreadCount)
    }

    @Test
    fun `a blocked member gets no copies, and the user can't add someone blocked`() = runTest {
        val id = fika()
        bjorn.blocked.blocked += "cecilia"
        relay.posted.clear()
        bjorn.repo.sendText(id, "Hej")
        assertEquals(listOf("astrid"), relay.posted.map { it.to })
        val row = bjorn.messageDao.messagesIn(id).single()
        assertEquals(MessageState.SENT.name, row.state)
        astrid.blocked.blocked += "dagny"
        assertThrows { astrid.repo.invite(id, listOf("dagny")) }
    }

    // ── Files ─────────────────────────────────────────────────────────────

    @Test
    fun `a file goes up once for every member, and each copy carries the same key`() = runTest {
        val id = fika()
        val key = ByteArray(32) { it.toByte() }
        coEvery { astrid.fileDrop.encrypt(any(), any(), any()) } returns EncryptedFile(key, "fika.pdf", "application/pdf", 1234, 1300)
        astrid.repo.sendFile(id, mockk(relaxed = true), "Menyn", null)
        val row = astrid.messageDao.messagesIn(id).single()
        assertEquals(MessageState.SENDING.name, row.state)
        // The background upload (TransferWorker → finishSend).
        astrid.repo.finishSend(row)
        assertEquals(listOf(listOf("bjorn", "cecilia")), astrid.uploads.map { it.sorted() })
        val bodies = relay.posted.filter { it.sender == "astrid" && it.plaintext.contains("\"msg\"") }
            .map { (GroupPacketCodec.decode(it.plaintext, relay.clock) as GroupPacketCodec.Decoded.Ok).packet.body!! }
        assertEquals(2, bodies.size)
        val files = bodies.map { FilePayload.decode(it)!! }
        assertEquals(setOf("a".repeat(32)), files.map { it.id }.toSet())
        // Here it's simply ours to open; who has it is per member.
        assertEquals("ready", astrid.messageDao.getById(row.id)!!.mediaState)
        assertTrue(files.all { it.key.contentEquals(key) && it.name == "fika.pdf" })
        fetchAll()
        val atBjorn = bjorn.messageDao.messagesIn(id).single()
        assertEquals("a".repeat(32), atBjorn.attachmentId)
        assertEquals("remote", atBjorn.mediaState)
    }

    private suspend fun assertThrows(block: suspend () -> Unit) {
        val failed = try {
            block()
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("expected an exception", failed)
    }

    private fun MessageEntity.toMessage() = com.sotto.app.domain.model.Message(
        id = id, conversationId = conversationId, senderId = senderId, recipientId = recipientId, plaintext = plaintext,
        state = MessageState.valueOf(state), timestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(timestamp), isOutgoing = isOutgoing,
    )
}
