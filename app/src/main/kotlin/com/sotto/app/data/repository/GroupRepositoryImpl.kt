package com.sotto.app.data.repository

import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.GroupDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ContactEntity
import com.sotto.app.data.local.entity.GroupEntity
import com.sotto.app.data.local.entity.GroupHeldEntity
import com.sotto.app.data.local.entity.GroupMemberEntity
import com.sotto.app.data.local.entity.GroupOutboxEntity
import com.sotto.app.data.local.entity.GroupReactionEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.media.FileDrop
import com.sotto.app.data.media.MediaStore
import com.sotto.app.domain.model.ContactTrust
import com.sotto.app.domain.model.FilePayload
import com.sotto.app.domain.model.FileState
import com.sotto.app.domain.model.Group
import com.sotto.app.domain.model.GroupCopy
import com.sotto.app.domain.model.GroupCopyState
import com.sotto.app.domain.model.GroupEvent
import com.sotto.app.domain.model.GroupKeyState
import com.sotto.app.domain.model.GroupMember
import com.sotto.app.domain.model.GroupMemberStatus
import com.sotto.app.domain.model.GroupReaction
import com.sotto.app.domain.model.GroupStatus
import com.sotto.app.domain.model.GroupVouch
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.MediaPayload
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageMedia
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.ReplyPayload
import com.sotto.app.domain.model.ReplyRef
import com.sotto.app.domain.model.UnknownPayload
import com.sotto.app.domain.model.quoteText
import com.sotto.app.domain.repository.GroupRepository
import com.sotto.app.group.GroupIds
import com.sotto.app.group.GroupPacket
import com.sotto.app.group.GroupPacketCodec
import com.sotto.app.group.GroupRef
import com.sotto.app.group.GroupRoster
import com.sotto.app.group.GroupSignatures
import com.sotto.app.group.RosterMember
import com.sotto.app.group.RosterRules
import com.sotto.app.worker.FileTransfers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group chats (ADR-32).
 *
 * Sending: a group message is stored once (under [GroupIds.rowId], the same on every member's
 * phone), then a copy of its packet goes to each active member, each copy sealed on that
 * member's own pairwise session ([GroupTransport]) and kept sealed in `group_outbox` until the
 * relay has it. A retry sends the same sealed bytes under the same client id; only copies that
 * failed go again.
 *
 * Receiving: [receive] gets every group packet after its envelope's signature checked out and
 * it decrypted, before anything one-to-one happens with it. Content is taken only from active
 * members of the newest roster held, sent to the user while they're active themselves; rosters
 * only when signed by the admin this phone holds (or one reached from it by signed handovers).
 */
@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val blockedUserDao: BlockedUserDao,
    private val transport: dagger.Lazy<GroupTransport>,
    private val memberKeys: MemberKeys,
    private val signatures: GroupSignatures,
    private val env: GroupEnvironment,
    private val mediaStore: MediaStore,
    private val fileDrop: FileDrop,
    private val transfers: FileTransfers,
    private val receipts: dagger.Lazy<ReceiptManager>,
) : GroupRepository {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val groupLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(groupId: String) = groupLocks.computeIfAbsent(groupId) { Mutex() }

    @Volatile
    private var activeGroupId: String? = null

    override fun setActiveGroup(groupId: String?) {
        activeGroupId = groupId
    }

    /** Sends the outbox in the background (WorkManager, once there's a network). */
    fun scheduleOutbox() = env.scheduleOutbox()

    // ── Reading ───────────────────────────────────────────────────────────

    override fun observeGroups(): Flow<List<Group>> = groupDao.observeChats().map { list -> list.map { toDomain(it) } }

    override fun observeInvitations(): Flow<List<Group>> = groupDao.observeInvitations().map { list -> list.map { toDomain(it) } }

    override fun observeGroup(groupId: String): Flow<Group?> = groupDao.observe(groupId).map { it?.let { g -> toDomain(g) } }

    override fun observeMembers(groupId: String): Flow<List<GroupMember>> =
        groupDao.observeMembers(groupId).map { list ->
            val me = env.me()
            list.map { m ->
                val contact = contactDao.getByUserId(m.userId)?.takeIf { !it.groupOnly }
                GroupMember(
                    userId = m.userId,
                    name = memberName(m, contact),
                    isAdmin = m.role == ROLE_ADMIN,
                    isMe = m.userId == me,
                    status = when (m.status) {
                        MEMBER_ACTIVE -> GroupMemberStatus.ACTIVE
                        MEMBER_INVITED -> GroupMemberStatus.INVITED
                        else -> GroupMemberStatus.GONE
                    },
                    vouchedTrust = vouchOf(m.vouchedTrust),
                    contactTrust = contact?.let { trustOf(it) },
                    keyState = if (m.userId == me) GroupKeyState.OK else GroupKeyState.fromWire(m.keyState),
                )
            }
        }

    override fun observeMessages(groupId: String): Flow<List<Message>> =
        messageDao.getMessagesForConversation(groupId).map { rows -> rows.map { it.toGroupDomain() } }

    override fun observeReactions(groupId: String): Flow<Map<String, List<GroupReaction>>> =
        combine(groupDao.observeReactions(groupId), groupDao.observeMembers(groupId)) { reactions, members ->
            val me = env.me()
            val names = members.associate { it.userId to it.name }
            reactions.groupBy { it.messageId }.mapValues { (_, list) ->
                list.map {
                    val contact = contactDao.getByUserId(it.reactorId)?.takeIf { c -> !c.groupOnly }
                    GroupReaction(
                        it.reactorId,
                        contact?.displayName ?: names[it.reactorId]?.ifBlank { null } ?: it.reactorId.take(8),
                        it.emoji,
                        it.reactorId == me,
                    )
                }
            }
        }

    override fun observeCopies(messageId: String): Flow<List<GroupCopy>> =
        groupDao.observeCopiesForMessage(messageId).map { copies -> copies.map { toCopy(it) } }

    override fun observeRosterDelivery(groupId: String): Flow<List<GroupCopy>> =
        groupDao.observeControlCopies(groupId).map { copies ->
            // The newest roster or invitation each member was sent.
            copies.filter { it.kind == GroupPacket.KIND_ROSTER || it.kind == GroupPacket.KIND_INVITE }
                .distinctBy { it.recipientId }
                .map { toCopy(it) }
        }

    private suspend fun toCopy(copy: GroupOutboxEntity): GroupCopy {
        val member = groupDao.getMember(copy.groupId, copy.recipientId)
        val contact = contactDao.getByUserId(copy.recipientId)?.takeIf { !it.groupOnly }
        return GroupCopy(
            recipientId = copy.recipientId,
            recipientName = member?.let { memberName(it, contact) } ?: contact?.displayName ?: copy.recipientId.take(8),
            state = when (copy.state) {
                COPY_QUEUED, COPY_SEALED -> GroupCopyState.SENDING
                COPY_SENT -> GroupCopyState.SENT
                COPY_DELIVERED -> GroupCopyState.DELIVERED
                COPY_READ -> GroupCopyState.READ
                COPY_SKIPPED -> GroupCopyState.SKIPPED
                else -> GroupCopyState.FAILED
            },
            error = copy.lastError,
        )
    }

    private suspend fun toDomain(g: GroupEntity): Group {
        val me = env.me()
        val members = groupDao.getMembers(g.id)
        val inviter = g.invitedBy?.let { id -> contactDao.getByUserId(id)?.displayName ?: id.take(8) }
        return Group(
            id = g.id,
            title = g.title,
            adminId = g.adminId,
            isAdmin = g.adminId == me,
            epoch = g.epoch,
            timerMs = g.timerMs,
            status = GroupStatus.fromWire(g.status),
            frozen = g.frozen,
            invitedBy = g.invitedBy,
            invitedByName = inviter,
            lastMessagePreview = g.lastMessagePreview,
            lastMessageTimestamp = g.lastMessageTimestamp?.let { Instant.fromEpochMilliseconds(it) },
            unreadCount = g.unreadCount,
            memberCount = members.count { it.status == MEMBER_ACTIVE },
            createdAt = Instant.fromEpochMilliseconds(g.createdAt),
        )
    }

    private fun memberName(m: GroupMemberEntity, contact: ContactEntity?): String =
        contact?.displayName ?: m.name.ifBlank { m.userId.take(8) }

    /** What each member of [groupId] is called on this phone. */
    private suspend fun memberNames(groupId: String): Map<String, String> =
        groupDao.getMembers(groupId).associate { it.userId to nameOf(groupId, it.userId) }

    private suspend fun nameOf(groupId: String, userId: String): String {
        val contact = contactDao.getByUserId(userId)?.takeIf { !it.groupOnly }
        val member = groupDao.getMember(groupId, userId)
        return contact?.displayName ?: member?.name?.ifBlank { null } ?: userId.take(8)
    }

    // ── Creating ──────────────────────────────────────────────────────────

    override suspend fun createGroup(title: String, memberIds: List<String>): String = withContext(NonCancellable) {
        val me = env.me() ?: throw IllegalStateException("No identity")
        val cleanTitle = RosterRules.cleanTitle(title)
        require(cleanTitle.isNotEmpty()) { "A group needs a name" }
        val others = memberIds.distinct().filter { it != me }
        require(others.isNotEmpty()) { "A group needs someone else" }
        require(others.size < GroupRoster.MAX_MEMBERS) { "At most ${GroupRoster.MAX_MEMBERS} members" }
        val myFp = env.myFingerprint() ?: throw IllegalStateException("No keys")
        val invited = others.map { id -> rosterEntryForContact(id, RosterMember.INVITED, previous = null) }
        val groupId = GroupIds.newId()
        val roster = RosterRules.sign(
            GroupRoster(
                groupId = groupId, epoch = 1, issuer = me, admin = me, title = cleanTitle, timerMs = 0,
                members = (invited + RosterMember(me, myFp, RosterRules.cleanName(env.myName()), RosterMember.ACTIVE, RosterMember.TRUST_NONE))
                    .sortedBy { it.userId },
            ),
            signatures,
        )
        lockFor(groupId).withLock {
            applyRoster(null, roster, GroupStatus.ACTIVE, invitedBy = null)
            addEvent(groupId, GroupEvent(GroupEvent.CREATED, listOf(me), listOf(env.myName())))
            val names = invited.map { nameOf(groupId, it.userId) }
            addEvent(groupId, GroupEvent(GroupEvent.INVITED, invited.map { it.userId }, names))
            sendRoster(roster, previous = null)
        }
        groupId
    }

    /** A roster entry for the user's contact [userId]: their pinned fingerprint and how the user verified them. */
    private suspend fun rosterEntryForContact(userId: String, status: String, previous: RosterMember?): RosterMember {
        if (blockedUserDao.isBlocked(userId)) throw IllegalArgumentException("Blocked")
        val contact = contactDao.getByUserId(userId)
        if (contact == null || contact.groupOnly || conversationDao.getByContactId(userId)?.isApproved != true) {
            throw IllegalArgumentException("Only contacts can be added")
        }
        val fp = memberKeys.pinnedFingerprint(userId) ?: throw IllegalStateException("No keys for ${userId.take(8)}")
        return RosterMember(
            userId = userId,
            fingerprint = fp,
            // The name they chose (they send it when accepting); never the user's nickname for them.
            name = previous?.name ?: RosterRules.cleanName(contact.profileName.orEmpty()),
            status = status,
            trust = trustLetter(contact),
        )
    }

    // ── Sending messages ──────────────────────────────────────────────────

    override suspend fun sendText(groupId: String, text: String, replyTo: Message?) = withContext(NonCancellable) {
        val body = text.trim()
        require(body.isNotEmpty()) { "Empty message" }
        val row = newOwnRow(groupId, body, replyTo)
        messageDao.insert(row)
        touchPreview(row)
        fanOut(row)
    }

    override suspend fun sendMedia(
        groupId: String,
        media: MessageMedia,
        bytes: ByteArray,
        caption: String,
        replyTo: Message?,
        hdBytes: ByteArray?,
    ) = withContext(NonCancellable) {
        require(bytes.isNotEmpty() && bytes.size <= MediaPayload.MAX_BYTES) { "Media too large" }
        require(hdBytes == null || (media.kind == MediaKind.PHOTO && hdBytes.size <= MediaPayload.HD_MAX_BYTES)) { "Bad HD copy" }
        val base = newOwnRow(groupId, caption, replyTo)
        val key = mediaStore.save(base.id, bytes)
        val hdKey = hdBytes?.let { fileDrop.encryptBytes(it, base.id + MediaStore.HD_SUFFIX) }
        val row = base.copy(
            mediaKind = media.kind.wire, mediaMime = media.mime, mediaKey = key,
            mediaDurationMs = media.durationMs, mediaWidth = media.width, mediaHeight = media.height,
            mediaSize = hdBytes?.size?.toLong() ?: 0,
            mediaState = if (hdKey != null) FileState.UPLOADING.wire else null,
            hdKey = hdKey,
        )
        try {
            messageDao.insert(row)
        } catch (e: Exception) {
            mediaStore.delete(base.id)
            if (hdKey != null) mediaStore.delete(base.id + MediaStore.HD_SUFFIX)
            throw e
        }
        touchPreview(row)
        if (hdKey != null) transfers.upload(row.id) else fanOut(row)
    }

    override suspend fun sendFile(groupId: String, uri: android.net.Uri, caption: String, replyTo: Message?) = withContext(NonCancellable) {
        val base = newOwnRow(groupId, caption, replyTo)
        val file = fileDrop.encrypt(uri, base.id, MessageRepositoryImpl.MAX_FILE_BYTES)
        val row = base.copy(
            mediaKind = MediaKind.FILE.wire, mediaMime = file.mime, mediaKey = file.key, mediaName = file.name,
            mediaSize = file.size, mediaState = FileState.UPLOADING.wire, thumbnail = file.thumbnail,
            mediaDurationMs = file.durationMs,
        )
        try {
            messageDao.insert(row)
        } catch (e: Exception) {
            mediaStore.delete(base.id)
            throw e
        }
        touchPreview(row)
        // Up once to the store for every member, in the background; the message follows (finishSend).
        transfers.upload(row.id)
    }

    /** A new row for our message in [groupId], not stored yet. */
    private suspend fun newOwnRow(groupId: String, text: String, replyTo: Message?): MessageEntity {
        val me = env.me() ?: throw IllegalStateException("No identity")
        val group = groupDao.get(groupId) ?: throw IllegalStateException("No such group")
        if (GroupStatus.fromWire(group.status) != GroupStatus.ACTIVE) throw IllegalStateException("Not a member")
        val logicalId = GroupIds.newId()
        val now = env.now()
        val reply = replyTo?.takeIf { it.conversationId == groupId }?.let { messageDao.getById(it.id) }
        return MessageEntity(
            id = GroupIds.rowId(groupId, me, logicalId),
            conversationId = groupId,
            senderId = me,
            recipientId = "",
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = text,
            state = MessageState.SENDING.name,
            timestamp = now,
            expiresAt = group.timerMs.takeIf { it > 0 }?.let { now + it },
            isOutgoing = true,
            replyToId = reply?.id,
            replyQuote = reply?.let { ReplyPayload.quoteOf(quoteText(MediaKind.fromWire(it.mediaKind), it.plaintext, it.mediaName.orEmpty())) },
            replyToOwn = reply?.senderId == me,
            groupId = groupId,
            groupMessageId = logicalId,
        )
    }

    /** What goes in [row]'s packet body: its text, or the media or file payload a 1:1 message would carry. */
    private suspend fun bodyOf(row: MessageEntity): String {
        if (row.mediaKind == MediaKind.FILE.wire) {
            val id = row.attachmentId ?: throw IllegalStateException("File not uploaded")
            val key = row.mediaKey ?: throw IllegalStateException("File without a key")
            return FilePayload.encode(
                id, key, row.mediaName ?: "file", row.mediaMime ?: "application/octet-stream",
                row.mediaSize, row.plaintext, null, row.thumbnail, row.mediaDurationMs, direct = false,
            )
        }
        val kind = MediaKind.fromWire(row.mediaKind)
        if (kind != null) {
            val key = row.mediaKey ?: throw IllegalStateException("Media without a key")
            val bytes = mediaStore.load(row.id, key) ?: throw IllegalStateException("Media file missing")
            val hd = row.hdKey?.let { hdKey ->
                MediaPayload.Hd(row.attachmentId ?: throw IllegalStateException("HD copy not uploaded"), hdKey, row.mediaSize)
            }
            val media = MessageMedia(kind, row.mediaMime.orEmpty(), row.mediaDurationMs, row.mediaWidth, row.mediaHeight)
            return MediaPayload.encode(media, bytes, row.plaintext, null, hd)
        }
        return row.plaintext
    }

    /** The reference a packet carries for the message [row] replies to. */
    private suspend fun replyRef(row: MessageEntity): GroupRef? {
        val target = row.replyToId?.let { messageDao.getById(it) } ?: return null
        val id = target.groupMessageId ?: return null
        return GroupRef(target.senderId, id)
    }

    /** Called by [MessageRepositoryImpl.finishSend] for a group message whose file goes through the store. */
    suspend fun finishSend(entity: MessageEntity) = withContext(NonCancellable) {
        if (!entity.isOutgoing || entity.groupId == null) return@withContext
        if (entity.state != MessageState.SENDING.name) return@withContext
        val recipients = recipientsFor(entity.groupId).map { it.userId }
        val ready = if (recipients.isEmpty()) {
            entity
        } else {
            transport.get().upload(entity, recipients)
        }
        fanOut(ready)
    }

    /** A file upload that won't go: the message fails (a retry uploads again, then sends). */
    suspend fun markSendFailed(entity: MessageEntity) {
        if (entity.state == MessageState.SENDING.name) messageDao.updateState(entity.id, MessageState.FAILED.name)
    }

    /** Active members to send [groupId]'s content to (not the user). */
    private suspend fun recipientsFor(groupId: String): List<GroupMemberEntity> {
        val me = env.me()
        return groupDao.getMembers(groupId).filter { it.status == MEMBER_ACTIVE && it.userId != me }
    }

    /** Queues and sends a copy of our message [row] for every active member. */
    private suspend fun fanOut(row: MessageEntity) {
        val groupId = row.groupId ?: return
        val me = env.me() ?: return
        val group = groupDao.get(groupId) ?: return
        val epoch = group.epoch
        val body = bodyOf(row)
        val ref = replyRef(row)
        val logicalId = row.groupMessageId ?: return
        val now = env.now()
        val copies = recipientsFor(groupId).map { member ->
            val packet = GroupPacket(
                kind = GroupPacket.KIND_MSG, groupId = groupId, id = logicalId, sender = me, to = member.userId,
                epoch = epoch, timestamp = row.timestamp, body = body,
                replyTo = ref, replyQuote = ref?.let { row.replyQuote.orEmpty().take(GroupPacketCodec.MAX_QUOTE) },
            )
            copyFor(groupId, row.id, member.userId, packet, now)
        }
        groupDao.insertCopies(copies)
        deliver(groupDao.getCopiesForMessage(row.id))
        refreshState(row.id)
    }

    /** A queued copy of [packet] for [recipient]; skipped for someone blocked, failed for someone without matching keys. */
    private suspend fun copyFor(groupId: String, messageId: String?, recipient: String, packet: GroupPacket, now: Long): GroupOutboxEntity {
        val me = env.me() ?: ""
        val clientId = GroupIds.copyClientId(packet.id, recipient)
        val member = groupDao.getMember(groupId, recipient)
        val blocked = blockedUserDao.isBlocked(recipient)
        val state = when {
            blocked || member?.status == MEMBER_GONE -> COPY_SKIPPED
            else -> COPY_QUEUED
        }
        return GroupOutboxEntity(
            id = clientId,
            groupId = groupId,
            messageId = messageId,
            recipientId = recipient,
            kind = packet.kind,
            packet = if (state == COPY_QUEUED) GroupPacketCodec.encode(packet) else null,
            relayId = ReceiptManager.relayMessageId(me, clientId),
            state = state,
            createdAt = now,
            updatedAt = now,
        )
    }

    private val sendPermits = Semaphore(SEND_PARALLELISM)

    /** Seals (once) and posts each queued or sealed copy in [copies]. */
    private suspend fun deliver(copies: List<GroupOutboxEntity>) = coroutineScope {
        copies.filter { it.state == COPY_QUEUED || it.state == COPY_SEALED }
            .map { copy -> async { sendPermits.withPermit { deliverOne(copy) } } }
            .awaitAll()
    }

    private suspend fun deliverOne(start: GroupOutboxEntity) {
        var copy = groupDao.getCopy(start.id) ?: return
        if (copy.state != COPY_QUEUED && copy.state != COPY_SEALED) return
        if (blockedUserDao.isBlocked(copy.recipientId)) {
            groupDao.setState(copy.id, COPY_SKIPPED, env.now())
            return
        }
        try {
            if (copy.state == COPY_QUEUED) {
                val member = groupDao.getMember(copy.groupId, copy.recipientId)
                if (member != null && member.keyState != GroupKeyState.OK.wire) {
                    // Keys not pinned (yet) or not the vouched ones: try once more, else this copy waits.
                    val state = memberKeys.ensure(member.userId, member.fingerprint, member.name)
                    groupDao.setKeyState(member.groupId, member.userId, state.wire)
                    if (state != GroupKeyState.OK) {
                        groupDao.setFailed(copy.id, COPY_FAILED, if (state == GroupKeyState.MISMATCH) ERROR_KEYS else ERROR_NO_KEYS, env.now())
                        return
                    }
                }
                val packet = copy.packet ?: throw IllegalStateException("Copy without a packet")
                val sealed = transport.get().seal(copy.recipientId, packet)
                // Kept before it leaves: from here a retry must send exactly these bytes.
                groupDao.setSealed(copy.id, sealed.ciphertext, sealed.nonce, sealed.signature, sealed.pqcSignature, env.now())
                copy = groupDao.getCopy(copy.id) ?: return
            }
            val sealed = GroupTransport.Sealed(
                copy.ciphertext ?: throw IllegalStateException("Sealed copy without bytes"),
                copy.nonce ?: ByteArray(0),
                copy.signature.orEmpty(),
                copy.pqcSignature.orEmpty(),
            )
            transport.get().post(copy.recipientId, sealed, copy.id)
            groupDao.setSent(copy.id, env.now())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w(TAG, "Copy to ${copy.recipientId.take(8)} failed: ${e.message}")
            groupDao.setFailed(copy.id, COPY_FAILED, e.message?.take(120) ?: e.javaClass.simpleName, env.now())
        }
    }

    /** Sets our message's state from its copies: sending, failed (some), sent, delivered (all), read (all). */
    private suspend fun refreshState(messageId: String) {
        val row = messageDao.getById(messageId) ?: return
        if (!row.isOutgoing || row.groupId == null) return
        // A file still on its way up: the message hasn't gone yet.
        if (row.mediaState == FileState.UPLOADING.wire && row.state == MessageState.SENDING.name) return
        val copies = groupDao.getCopiesForMessage(messageId).filter { it.state != COPY_SKIPPED }
        val state = when {
            copies.any { it.state == COPY_QUEUED || it.state == COPY_SEALED } -> MessageState.SENDING
            copies.any { it.state == COPY_FAILED } -> MessageState.FAILED
            copies.isNotEmpty() && copies.all { it.state == COPY_READ } -> MessageState.SEEN
            copies.isNotEmpty() && copies.all { it.state == COPY_READ || it.state == COPY_DELIVERED } -> MessageState.DELIVERED
            else -> MessageState.SENT
        }
        if (row.state != state.name) messageDao.updateState(messageId, state.name)
    }

    /** Our new message is the group's latest. */
    private suspend fun touchPreview(row: MessageEntity) {
        val groupId = row.groupId ?: return
        groupDao.updatePreview(groupId, previewWithSender(row, ""), row.timestamp, 0)
    }

    override suspend fun retry(messageId: String) = withContext(NonCancellable) {
        val row = messageDao.getById(messageId) ?: return@withContext
        if (!row.isOutgoing || row.groupId == null) return@withContext
        if (row.state != MessageState.FAILED.name) return@withContext
        val copies = groupDao.getCopiesForMessage(messageId)
        if (copies.isEmpty()) {
            // It failed before any copy was made (its file didn't go up): start over.
            messageDao.updateState(messageId, MessageState.SENDING.name)
            if (row.mediaKind == MediaKind.FILE.wire || row.hdKey != null) {
                messageDao.setMediaState(messageId, FileState.UPLOADING.wire)
                transfers.upload(messageId)
            } else {
                fanOut(row)
            }
            return@withContext
        }
        for (copy in copies.filter { it.state == COPY_FAILED }) {
            // Sealed before it failed: the same bytes go again. Never sealed: it's sealed now.
            groupDao.setState(copy.id, if (copy.ciphertext != null) COPY_SEALED else COPY_QUEUED, env.now())
        }
        messageDao.updateState(messageId, MessageState.SENDING.name)
        deliver(groupDao.getCopiesForMessage(messageId))
        refreshState(messageId)
    }

    override suspend fun flushOutbox(): Boolean = withContext(NonCancellable) {
        val waiting = groupDao.getByStates(listOf(COPY_QUEUED, COPY_SEALED), OUTBOX_BATCH)
        // Control packets and reactions are retried by themselves; a message's failed copies wait for a tap.
        val failedControl = groupDao.getByStates(listOf(COPY_FAILED), OUTBOX_BATCH)
            .filter { it.messageId == null && it.attempts < MAX_CONTROL_ATTEMPTS && it.lastError != ERROR_KEYS }
        for (copy in failedControl) {
            groupDao.setState(copy.id, if (copy.ciphertext != null) COPY_SEALED else COPY_QUEUED, env.now())
        }
        val all = waiting + failedControl
        deliver(all.mapNotNull { groupDao.getCopy(it.id) })
        all.mapNotNull { it.messageId }.distinct().forEach { refreshState(it) }
        groupDao.pruneControlCopies(env.now() - CONTROL_COPY_KEEP_MS)
        groupDao.getByStates(listOf(COPY_QUEUED, COPY_SEALED), 1).isNotEmpty() ||
            groupDao.getByStates(listOf(COPY_FAILED), OUTBOX_BATCH).any { it.messageId == null && it.attempts < MAX_CONTROL_ATTEMPTS && it.lastError != ERROR_KEYS }
    }

    /** Our messages left SENDING by a process death: their copies are sent again (the same bytes). */
    suspend fun resumeInterrupted() {
        flushOutbox()
        for (row in messageDao.getSendingMessages().filter { it.groupId != null }) {
            val copies = groupDao.getCopiesForMessage(row.id)
            if (copies.isEmpty() && row.mediaState != FileState.UPLOADING.wire) {
                // It never got as far as its copies.
                fanOut(row)
            } else {
                refreshState(row.id)
            }
        }
    }

    // ── Reactions ─────────────────────────────────────────────────────────

    override suspend fun react(groupId: String, message: Message, emoji: String?) = withContext(NonCancellable) {
        val me = env.me() ?: throw IllegalStateException("No identity")
        val target = messageDao.getById(message.id) ?: return@withContext
        if (target.groupId != groupId) throw IllegalArgumentException("Not a message of this group")
        val logicalId = target.groupMessageId ?: return@withContext
        val group = groupDao.get(groupId) ?: return@withContext
        if (GroupStatus.fromWire(group.status) != GroupStatus.ACTIVE) throw IllegalStateException("Not a member")
        val current = groupDao.getReactions(target.id).firstOrNull { it.reactorId == me }?.emoji
        if (current == emoji) return@withContext
        if (emoji == null) groupDao.deleteReaction(target.id, me) else groupDao.upsertReaction(GroupReactionEntity(target.id, me, emoji))
        val packetId = GroupIds.newId()
        val now = env.now()
        val copies = recipientsFor(groupId).map { member ->
            copyFor(
                groupId, null, member.userId,
                GroupPacket(
                    kind = GroupPacket.KIND_REACT, groupId = groupId, id = packetId, sender = me, to = member.userId,
                    epoch = group.epoch, timestamp = now, target = GroupRef(target.senderId, logicalId), emoji = emoji.orEmpty(),
                ),
                now,
            )
        }
        groupDao.insertCopies(copies)
        deliver(copies)
        if (copies.any { groupDao.getCopy(it.id)?.state == COPY_FAILED }) env.scheduleOutbox()
    }

    // ── Invitations, leaving ──────────────────────────────────────────────

    override suspend fun acceptInvitation(groupId: String) = withContext(NonCancellable) {
        lockFor(groupId).withLock {
            val group = groupDao.get(groupId) ?: return@withLock
            if (GroupStatus.fromWire(group.status) != GroupStatus.INVITED) return@withLock
            groupDao.setStatus(groupId, GroupStatus.JOINING.wire)
            sendControl(group, group.adminId, GroupPacket.KIND_ACCEPT, name = RosterRules.cleanName(env.myName()))
            env.cancelNotification(groupId)
        }
        Unit
    }

    override suspend fun rejectInvitation(groupId: String) = withContext(NonCancellable) {
        lockFor(groupId).withLock {
            val group = groupDao.get(groupId) ?: return@withLock
            if (GroupStatus.fromWire(group.status) != GroupStatus.INVITED) return@withLock
            groupDao.setStatus(groupId, GroupStatus.REJECTED.wire)
            sendControl(group, group.adminId, GroupPacket.KIND_REJECT)
            env.cancelNotification(groupId)
        }
    }

    override suspend fun leave(groupId: String) = withContext(NonCancellable) {
        val me = env.me() ?: throw IllegalStateException("No identity")
        lockFor(groupId).withLock {
            val group = groupDao.get(groupId) ?: return@withLock
            val status = GroupStatus.fromWire(group.status)
            if (status != GroupStatus.ACTIVE && status != GroupStatus.JOINING) return@withLock
            if (group.adminId == me && !group.frozen && recipientsFor(groupId).isNotEmpty()) {
                // The admin hands over first, so the group isn't left without one.
                handOverLocked(group)
            }
            val current = groupDao.get(groupId) ?: return@withLock
            val tellAll = groupDao.getMembers(groupId).filter { it.userId != me && it.status != MEMBER_GONE }.map { it.userId }
            for (to in (tellAll + current.adminId).distinct().filter { it != me }) {
                sendControl(current, to, GroupPacket.KIND_LEAVE)
            }
            groupDao.setStatus(groupId, GroupStatus.LEFT.wire)
            addEvent(groupId, GroupEvent(GroupEvent.YOU_LEFT))
        }
    }

    override suspend fun deleteLocally(groupId: String) = withContext(NonCancellable) {
        lockFor(groupId).withLock {
            val group = groupDao.get(groupId) ?: return@withLock
            val status = GroupStatus.fromWire(group.status)
            if (status == GroupStatus.ACTIVE || status == GroupStatus.JOINING) throw IllegalStateException("Leave the group first")
            messageDao.deleteAllForConversation(groupId)
            groupDao.deleteCopiesForGroup(groupId)
            groupDao.deleteHeldForGroup(groupId)
            groupDao.pruneOrphanReactions()
            if (status == GroupStatus.INVITED) {
                groupDao.setStatus(groupId, GroupStatus.REJECTED.wire)
            } else {
                // Kept as declined, so later packets for it are dropped without asking anyone.
                groupDao.upsert(group.copy(status = GroupStatus.REJECTED.wire, lastMessagePreview = null, unreadCount = 0))
                groupDao.deleteMembers(groupId)
            }
            env.cancelNotification(groupId)
        }
    }

    /** A control packet of [kind] for [to] about [group], queued and sent (retried by the outbox). */
    private suspend fun sendControl(
        group: GroupEntity,
        to: String,
        kind: String,
        name: String? = null,
        roster: GroupRoster? = null,
        sendNow: Boolean = true,
    ) {
        val me = env.me() ?: return
        val now = env.now()
        val packet = GroupPacket(
            kind = kind, groupId = group.id, id = GroupIds.newId(), sender = me, to = to,
            epoch = roster?.epoch ?: group.epoch, timestamp = now, name = name, roster = roster,
        )
        val copy = copyFor(group.id, null, to, packet, now)
        groupDao.insertCopies(listOf(copy))
        if (!sendNow) {
            env.scheduleOutbox()
            return
        }
        deliver(listOf(copy))
        if (groupDao.getCopy(copy.id)?.state == COPY_FAILED) env.scheduleOutbox()
    }

    // ── Admin ─────────────────────────────────────────────────────────────

    override suspend fun rename(groupId: String, title: String) = asAdmin(groupId) { group, roster ->
        val clean = RosterRules.cleanTitle(title)
        require(clean.isNotEmpty()) { "A group needs a name" }
        if (clean == roster.title) return@asAdmin
        issue(group, roster.copy(title = clean))
    }

    override suspend fun setTimer(groupId: String, timerMs: Long) = asAdmin(groupId) { group, roster ->
        require(timerMs in 0..GroupRoster.MAX_TIMER_MS) { "Timer" }
        if (timerMs == roster.timerMs) return@asAdmin
        issue(group, roster.copy(timerMs = timerMs))
    }

    override suspend fun invite(groupId: String, memberIds: List<String>) = asAdmin(groupId) { group, roster ->
        val me = env.me() ?: return@asAdmin
        val added = memberIds.distinct().filter { it != me && roster.member(it) == null }
        if (added.isEmpty()) return@asAdmin
        require(roster.members.size + added.size <= GroupRoster.MAX_MEMBERS) { "At most ${GroupRoster.MAX_MEMBERS} members" }
        val entries = added.map { rosterEntryForContact(it, RosterMember.INVITED, previous = null) }
        issue(group, roster.copy(members = (roster.members + entries).sortedBy { it.userId }))
    }

    override suspend fun remove(groupId: String, memberId: String) = asAdmin(groupId) { group, roster ->
        val me = env.me() ?: return@asAdmin
        require(memberId != me) { "Leave instead" }
        if (roster.member(memberId) == null) return@asAdmin
        issue(group, roster.copy(members = roster.members.filter { it.userId != memberId }))
    }

    override suspend fun makeAdmin(groupId: String, memberId: String) = asAdmin(groupId) { group, roster ->
        require(roster.isActive(memberId)) { "Only an active member can be admin" }
        val me = env.me() ?: return@asAdmin
        if (memberId == me) return@asAdmin
        issue(group, roster.copy(admin = memberId))
    }

    override suspend fun deleteGroup(groupId: String) = asAdmin(groupId) { group, roster ->
        issue(group, roster.copy(deleted = true))
    }

    override suspend fun groupsToHandOver(): List<Group> {
        val me = env.me() ?: return emptyList()
        return groupDao.getAll().filter {
            it.adminId == me && !it.frozen && GroupStatus.fromWire(it.status) == GroupStatus.ACTIVE && recipientsFor(it.id).isNotEmpty()
        }.map { toDomain(it) }
    }

    override suspend fun handOverAll(): Int = withContext(NonCancellable) {
        var count = 0
        for (g in groupsToHandOver()) {
            lockFor(g.id).withLock {
                groupDao.get(g.id)?.let { handOverLocked(it); count++ }
            }
        }
        count
    }

    /** Hands [group] (ours) to its longest-standing other active member. */
    private suspend fun handOverLocked(group: GroupEntity) {
        val roster = parseRoster(group.roster)
        val me = env.me() ?: return
        val heir = roster.activeMembers.firstOrNull { it.userId != me && memberUsable(group.id, it.userId) }
            ?: roster.activeMembers.firstOrNull { it.userId != me } ?: return
        issue(group, roster.copy(admin = heir.userId))
    }

    private suspend fun memberUsable(groupId: String, userId: String): Boolean =
        groupDao.getMember(groupId, userId)?.let { it.status == MEMBER_ACTIVE && it.keyState == GroupKeyState.OK.wire } == true

    /** Runs [change] for a group the user is the admin of, under its lock. */
    private suspend fun asAdmin(groupId: String, change: suspend (GroupEntity, GroupRoster) -> Unit) = withContext(NonCancellable) {
        val me = env.me() ?: throw IllegalStateException("No identity")
        lockFor(groupId).withLock {
            val group = groupDao.get(groupId) ?: throw IllegalStateException("No such group")
            if (group.adminId != me) throw IllegalStateException("Only the admin can change the group")
            if (group.frozen || GroupStatus.fromWire(group.status) != GroupStatus.ACTIVE) throw IllegalStateException("The group can't change")
            change(group, parseRoster(group.roster))
        }
    }

    /**
     * Issues [draft] (the stored roster, changed) as the next epoch: signed, taken here, and sent
     * to everyone in the old or new roster. A new admin gets a signed handover in it.
     * Call with the group's lock held.
     */
    private suspend fun issue(group: GroupEntity, draft: GroupRoster, sendNow: Boolean = true) {
        val me = env.me() ?: return
        val previous = parseRoster(group.roster)
        val epoch = previous.epoch + 1
        var transfers = previous.transfers
        if (draft.admin != me) transfers = transfers + RosterRules.transfer(group.id, epoch, me, draft.admin, signatures)
        val refreshed = draft.members.map { m ->
            if (m.userId == me) return@map m.copy(name = RosterRules.cleanName(env.myName()))
            // Our own contacts: our pin and how we verified them. Members we only know through
            // the group keep the fingerprint and vouch they came with.
            val contact = contactDao.getByUserId(m.userId)
            if (contact != null && !contact.groupOnly) {
                val fp = memberKeys.pinnedFingerprint(m.userId) ?: m.fingerprint
                m.copy(fingerprint = fp, trust = trustLetter(contact))
            } else {
                m
            }
        }
        val roster = RosterRules.sign(
            draft.copy(
                epoch = epoch, issuer = me, members = refreshed,
                transfers = transfers.takeLast(GroupRoster.MAX_TRANSFERS),
            ),
            signatures,
        )
        RosterRules.validate(roster)?.let { throw IllegalStateException("Roster: $it") }
        applyRoster(group, roster, null, invitedBy = null)
        sendRoster(roster, previous, sendNow)
    }

    /** Sends [roster] (ours) to everyone in it or in [previous]: an invitation to those newly invited. */
    private suspend fun sendRoster(roster: GroupRoster, previous: GroupRoster?, sendNow: Boolean = true) {
        val me = env.me() ?: return
        val group = groupDao.get(roster.groupId) ?: return
        val recipients = (roster.members.map { it.userId } + previous?.members?.map { it.userId }.orEmpty())
            .distinct().filter { it != me }
        val packetId = GroupIds.newId()
        val now = env.now()
        val copies = recipients.map { to ->
            val newlyInvited = roster.member(to)?.status == RosterMember.INVITED && previous?.member(to) == null
            val kind = if (newlyInvited) GroupPacket.KIND_INVITE else GroupPacket.KIND_ROSTER
            copyFor(
                group.id, null, to,
                GroupPacket(kind = kind, groupId = group.id, id = packetId, sender = me, to = to, epoch = roster.epoch, timestamp = now, roster = roster),
                now,
            )
        }
        groupDao.insertCopies(copies)
        if (!sendNow) {
            // While messages are being fetched: sent in the background, not in the fetch's way.
            env.scheduleOutbox()
            return
        }
        deliver(copies)
        if (copies.any { groupDao.getCopy(it.id)?.state == COPY_FAILED }) env.scheduleOutbox()
    }

    // ── Taking rosters ────────────────────────────────────────────────────

    /**
     * Stores [roster] (already verified) for its group, with its members and their keys, records
     * what changed as events, and sets the user's own status. [previousGroup] is the stored row
     * (null for an invitation or a new group). Call with the group's lock held.
     */
    private suspend fun applyRoster(previousGroup: GroupEntity?, roster: GroupRoster, status: GroupStatus?, invitedBy: String?) {
        val me = env.me() ?: return
        val previous = previousGroup?.let { runCatching { parseRoster(it.roster) }.getOrNull() }
        val oldStatus = previousGroup?.let { GroupStatus.fromWire(it.status) }
        val newStatus = status ?: when {
            roster.deleted -> GroupStatus.ENDED
            roster.isActive(me) -> GroupStatus.ACTIVE
            roster.member(me)?.status == RosterMember.INVITED -> if (oldStatus == GroupStatus.JOINING) GroupStatus.JOINING else GroupStatus.INVITED
            oldStatus == GroupStatus.INVITED -> GroupStatus.REJECTED // invitation withdrawn
            else -> GroupStatus.REMOVED
        }
        val now = env.now()
        groupDao.upsert(
            GroupEntity(
                id = roster.groupId,
                title = roster.title,
                adminId = roster.admin,
                epoch = roster.epoch,
                timerMs = roster.timerMs,
                status = newStatus.wire,
                roster = json.encodeToString(GroupRoster.serializer(), roster),
                invitedBy = if (newStatus == GroupStatus.INVITED || newStatus == GroupStatus.JOINING) invitedBy ?: previousGroup?.invitedBy else null,
                frozen = previousGroup?.frozen ?: false,
                lastMessagePreview = previousGroup?.lastMessagePreview,
                lastMessageTimestamp = previousGroup?.lastMessageTimestamp,
                unreadCount = previousGroup?.unreadCount ?: 0,
                createdAt = previousGroup?.createdAt ?: now,
            ),
        )
        val oldMembers = groupDao.getMembers(roster.groupId).associateBy { it.userId }
        val members = roster.members.map { m ->
            val old = oldMembers[m.userId]
            val keyState = when {
                m.userId == me -> GroupKeyState.OK
                // Keys are needed once there's something to say to them: as a member, or for the admin.
                newStatus == GroupStatus.ACTIVE && m.status == RosterMember.ACTIVE -> memberKeys.ensure(m.userId, m.fingerprint, m.name)
                old != null && old.fingerprint == m.fingerprint -> GroupKeyState.fromWire(old.keyState)
                else -> GroupKeyState.PENDING
            }
            GroupMemberEntity(
                groupId = roster.groupId,
                userId = m.userId,
                name = m.name,
                role = if (m.userId == roster.admin) ROLE_ADMIN else ROLE_MEMBER,
                // A member who left or deleted their account stays gone until the admin's roster drops them.
                status = if (old?.status == MEMBER_GONE && old.fingerprint == m.fingerprint) MEMBER_GONE
                else if (m.status == RosterMember.ACTIVE) MEMBER_ACTIVE else MEMBER_INVITED,
                fingerprint = m.fingerprint,
                vouchedTrust = m.trust,
                keyState = keyState.wire,
            )
        }
        groupDao.replaceMembers(roster.groupId, members)
        if (previous != null) recordChanges(roster.groupId, previous, roster, oldStatus, newStatus, members)
        members.filter { it.keyState == GroupKeyState.MISMATCH.wire && oldMembers[it.userId]?.keyState != GroupKeyState.MISMATCH.wire }
            .forEach { addEvent(roster.groupId, GroupEvent(GroupEvent.KEYS_MISMATCH, listOf(it.userId), listOf(nameOf(roster.groupId, it.userId)))) }
        if (newStatus == GroupStatus.ENDED || newStatus == GroupStatus.REMOVED || newStatus == GroupStatus.REJECTED) {
            groupDao.deleteHeldForGroup(roster.groupId)
        }
    }

    /** Events for what changed from [old] to [new]. */
    private suspend fun recordChanges(
        groupId: String,
        old: GroupRoster,
        new: GroupRoster,
        oldStatus: GroupStatus?,
        newStatus: GroupStatus,
        members: List<GroupMemberEntity>,
    ) {
        val me = env.me() ?: return
        fun name(id: String) = members.firstOrNull { it.userId == id }?.name?.ifBlank { null } ?: old.member(id)?.name?.ifBlank { null } ?: id.take(8)
        suspend fun shown(id: String) = contactDao.getByUserId(id)?.takeIf { !it.groupOnly }?.displayName ?: name(id)
        if (newStatus == GroupStatus.ACTIVE && oldStatus != GroupStatus.ACTIVE) addEvent(groupId, GroupEvent(GroupEvent.YOU_JOINED))
        val joined = new.activeMembers.filter { it.userId != me && !old.isActive(it.userId) }
        if (joined.isNotEmpty() && oldStatus == GroupStatus.ACTIVE) {
            addEvent(groupId, GroupEvent(GroupEvent.JOINED, joined.map { it.userId }, joined.map { shown(it.userId) }))
        }
        val invited = new.members.filter { it.status == RosterMember.INVITED && old.member(it.userId) == null && it.userId != me }
        if (invited.isNotEmpty() && oldStatus == GroupStatus.ACTIVE) {
            addEvent(groupId, GroupEvent(GroupEvent.INVITED, invited.map { it.userId }, invited.map { shown(it.userId) }))
        }
        val goneIds = old.members.filter { new.member(it.userId) == null && it.userId != me }
        for (m in goneIds) {
            val type = if (m.status == RosterMember.INVITED) GroupEvent.DECLINED else GroupEvent.LEFT
            addEvent(groupId, GroupEvent(type, listOf(m.userId), listOf(shown(m.userId))))
        }
        if (new.title != old.title) addEvent(groupId, GroupEvent(GroupEvent.TITLE, listOf(new.issuer), listOf(shown(new.issuer)), new.title))
        if (new.timerMs != old.timerMs) addEvent(groupId, GroupEvent(GroupEvent.TIMER, listOf(new.issuer), listOf(shown(new.issuer)), new.timerMs.toString()))
        if (new.admin != old.admin) addEvent(groupId, GroupEvent(GroupEvent.ADMIN, listOf(new.admin), listOf(if (new.admin == me) "" else shown(new.admin))))
        if (newStatus == GroupStatus.ENDED && oldStatus != GroupStatus.ENDED) addEvent(groupId, GroupEvent(GroupEvent.ENDED))
        if (newStatus == GroupStatus.REMOVED && oldStatus == GroupStatus.ACTIVE) addEvent(groupId, GroupEvent(GroupEvent.YOU_REMOVED))
    }

    private suspend fun addEvent(groupId: String, event: GroupEvent) {
        val me = env.me() ?: ""
        val now = env.now()
        messageDao.insert(
            MessageEntity(
                id = "gevent-" + GroupIds.newId(),
                conversationId = groupId,
                senderId = me,
                recipientId = "",
                encryptedBody = ByteArray(0),
                nonce = ByteArray(0),
                plaintext = GroupEvent.encode(event),
                state = MessageState.GROUP_EVENT.name,
                timestamp = now,
                expiresAt = null,
                isOutgoing = false,
                groupId = groupId,
            ),
        )
    }

    // ── Receiving ─────────────────────────────────────────────────────────

    /** A group packet whose envelope checked out, from [sender]. */
    class Inbound(
        /** The relay's id for this envelope. */
        val relayId: String,
        val sender: String,
        val plaintext: String,
        val timestamp: Long,
        /** The fingerprint of the keys the envelope was verified with. */
        val senderFingerprint: String,
        /** Keys from the relay that verified it, for a sender this phone has none pinned for (else null). */
        val newSenderKeys: VerifiedKeys?,
    )

    /**
     * Handles one group packet. Never throws for a bad packet: what can't be taken is dropped
     * (the caller acknowledges it), what arrived early is held.
     */
    suspend fun receive(inbound: Inbound) {
        val me = env.me() ?: return
        val packet = when (val decoded = GroupPacketCodec.decode(inbound.plaintext, env.now())) {
            is GroupPacketCodec.Decoded.Ok -> decoded.packet
            is GroupPacketCodec.Decoded.Invalid -> return drop(inbound, "invalid: ${decoded.reason}")
            GroupPacketCodec.Decoded.Unsupported -> return drop(inbound, "newer group protocol")
        }
        // The packet's sender and recipient must be the envelope's.
        if (packet.sender != inbound.sender) return drop(inbound, "sender doesn't match the envelope")
        if (packet.to != me) return drop(inbound, "not addressed to us")
        lockFor(packet.groupId).withLock {
            when (packet.kind) {
                GroupPacket.KIND_INVITE -> onInvite(packet, inbound)
                GroupPacket.KIND_ROSTER -> onRoster(packet, inbound)
                GroupPacket.KIND_ACCEPT, GroupPacket.KIND_REJECT -> onAnswer(packet, inbound)
                GroupPacket.KIND_LEAVE -> onLeave(packet, inbound)
                GroupPacket.KIND_MSG, GroupPacket.KIND_REACT -> onContent(packet, inbound, fromHeld = false)
            }
        }
    }

    private fun drop(inbound: Inbound, why: String) {
        if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w(TAG, "Dropped group packet from ${inbound.sender.take(8)}: $why")
    }

    /** Someone the user knows and talks to: a contact (not group-only), accepted, not blocked. */
    private suspend fun isTrustedContact(userId: String): Boolean {
        val contact = contactDao.getByUserId(userId) ?: return false
        if (contact.groupOnly || blockedUserDao.isBlocked(userId)) return false
        return conversationDao.getByContactId(userId)?.isApproved == true
    }

    private suspend fun onInvite(packet: GroupPacket, inbound: Inbound) {
        val me = env.me() ?: return
        val roster = packet.roster ?: return
        val existing = groupDao.get(packet.groupId)
        val status = existing?.let { GroupStatus.fromWire(it.status) }
        if (status == GroupStatus.INVITED || status == GroupStatus.JOINING || status == GroupStatus.ACTIVE) {
            // We know this group: it's an update like any other.
            return onRoster(packet, inbound)
        }
        // Invitations only from people the user knows and accepted: never from a stranger.
        if (!isTrustedContact(inbound.sender)) return drop(inbound, "invitation from someone not a contact")
        if (existing != null && roster.epoch <= existing.epoch) return drop(inbound, "old invitation")
        val verdict = verify(roster, null, inbound.sender)
        if (verdict != RosterRules.Verdict.Accept) return drop(inbound, "invitation: $verdict")
        if (roster.member(me)?.status != RosterMember.INVITED) return drop(inbound, "invitation doesn't invite us")
        if (roster.deleted) return drop(inbound, "invitation to a deleted group")
        applyRoster(null, roster, GroupStatus.INVITED, invitedBy = inbound.sender)
        groupDao.get(packet.groupId)?.let { env.notifyInvitation(it, nameOf(it.id, inbound.sender)) }
    }

    /** The signing keys this phone has pinned for everyone [roster] (or [stored]) may need checked. */
    private suspend fun pinnedKeys(roster: GroupRoster, stored: GroupRoster?): Map<String, com.sotto.app.group.SigningKeys?> {
        val ids = (roster.transfers.flatMap { listOf(it.from, it.to) } + listOf(roster.issuer, roster.admin) +
            listOfNotNull(stored?.admin)).distinct()
        return ids.associateWith { memberKeys.signingKeys(it) }
    }

    private suspend fun onRoster(packet: GroupPacket, inbound: Inbound) {
        val roster = packet.roster ?: return
        val group = groupDao.get(packet.groupId) ?: return drop(inbound, "roster for an unknown group")
        val status = GroupStatus.fromWire(group.status)
        if (status != GroupStatus.INVITED && status != GroupStatus.JOINING && status != GroupStatus.ACTIVE) {
            return drop(inbound, "roster for a group we're not in")
        }
        val stored = parseRoster(group.roster)
        var verdict = verify(roster, stored, inbound.sender)
        if (verdict is RosterRules.Verdict.NeedKeys) {
            // An admin (or one in the handover chain) whose keys we haven't fetched: they're
            // members of the roster we hold, so their vouched fingerprint says which keys to take.
            val need = verdict.userId
            val vouched = stored.member(need) ?: roster.member(need)?.takeIf { stored.member(roster.issuer) != null }
            if (vouched != null) {
                val state = memberKeys.ensure(need, vouched.fingerprint, vouched.name)
                groupDao.setKeyState(group.id, need, state.wire)
                verdict = verify(roster, stored, inbound.sender)
            }
        }
        when (verdict) {
            RosterRules.Verdict.Accept -> Unit
            RosterRules.Verdict.Stale -> return
            is RosterRules.Verdict.NeedKeys -> return hold(packet, inbound)
            is RosterRules.Verdict.Reject -> return drop(inbound, "roster: ${verdict.reason}")
        }
        applyRoster(group, roster, null, invitedBy = null)
        releaseHeld(group.id)
    }

    private suspend fun verify(roster: GroupRoster, stored: GroupRoster?, sender: String): RosterRules.Verdict {
        val keys = pinnedKeys(roster, stored)
        return RosterRules.verifyUpdate(roster, stored, sender, { id -> keys[id] }, signatures)
    }

    /** An invitee's answer, to us as the admin. */
    private suspend fun onAnswer(packet: GroupPacket, inbound: Inbound) {
        val me = env.me() ?: return
        val group = groupDao.get(packet.groupId) ?: return drop(inbound, "answer for an unknown group")
        if (group.adminId != me || group.frozen || GroupStatus.fromWire(group.status) != GroupStatus.ACTIVE) {
            return drop(inbound, "answer, but we're not the admin")
        }
        val roster = parseRoster(group.roster)
        val entry = roster.member(inbound.sender) ?: return drop(inbound, "answer from someone not invited")
        if (inbound.senderFingerprint != entry.fingerprint) return drop(inbound, "answer not signed with the invited keys")
        if (packet.kind == GroupPacket.KIND_REJECT) {
            if (entry.status != RosterMember.INVITED) return drop(inbound, "decline from a member")
            issue(group, roster.copy(members = roster.members.filter { it.userId != inbound.sender }), sendNow = false)
            return
        }
        if (entry.status == RosterMember.ACTIVE) {
            // A repeat (our roster may not have reached them): send them the current one again.
            sendControl(group, inbound.sender, GroupPacket.KIND_ROSTER, roster = roster, sendNow = false)
            return
        }
        if (blockedUserDao.isBlocked(inbound.sender)) return drop(inbound, "acceptance from someone blocked")
        val name = RosterRules.cleanName(packet.name.orEmpty())
        issue(
            group,
            roster.copy(members = roster.members.map { if (it.userId == inbound.sender) it.copy(status = RosterMember.ACTIVE, name = name) else it }),
            sendNow = false,
        )
    }

    private suspend fun onLeave(packet: GroupPacket, inbound: Inbound) {
        val me = env.me() ?: return
        val group = groupDao.get(packet.groupId) ?: return drop(inbound, "leave for an unknown group")
        val member = groupDao.getMember(group.id, inbound.sender) ?: return drop(inbound, "leave from someone not in it")
        if (member.status == MEMBER_GONE) return
        // Only from the member themselves, with the keys the admin vouched for.
        if (inbound.senderFingerprint != member.fingerprint) return drop(inbound, "leave not signed with the vouched keys")
        // Anyone may take themselves out: we stop sending to them at once, whatever the roster says.
        groupDao.markGone(group.id, inbound.sender)
        if (group.adminId == me && !group.frozen && GroupStatus.fromWire(group.status) == GroupStatus.ACTIVE) {
            val roster = parseRoster(group.roster)
            if (roster.member(inbound.sender) != null) {
                issue(group, roster.copy(members = roster.members.filter { it.userId != inbound.sender }), sendNow = false)
            }
        } else if (GroupStatus.fromWire(group.status) == GroupStatus.ACTIVE) {
            addEvent(group.id, GroupEvent(GroupEvent.LEFT, listOf(inbound.sender), listOf(nameOf(group.id, inbound.sender))))
        }
    }

    /** A message or reaction. [fromHeld]: it waited in `group_held` (it can't be held again). */
    private suspend fun onContent(packet: GroupPacket, inbound: Inbound, fromHeld: Boolean) {
        val me = env.me() ?: return
        val group = groupDao.get(packet.groupId) ?: return drop(inbound, "content for an unknown group")
        when (GroupStatus.fromWire(group.status)) {
            GroupStatus.ACTIVE -> Unit
            // Accepted, and the roster that makes us a member is on its way.
            GroupStatus.JOINING -> return if (fromHeld) drop(inbound, "still joining") else hold(packet, inbound)
            else -> return drop(inbound, "content for a group we're not in")
        }
        val roster = parseRoster(group.roster)
        if (packet.epoch > roster.epoch) {
            // The sender has a newer roster than we do: wait for ours.
            return if (fromHeld) drop(inbound, "epoch still ahead") else hold(packet, inbound)
        }
        if (!roster.isActive(me)) return drop(inbound, "we're not an active member")
        val entry = roster.member(inbound.sender)
        if (entry == null || entry.status != RosterMember.ACTIVE) return drop(inbound, "sender not an active member")
        val member = groupDao.getMember(group.id, inbound.sender)
        if (member == null || member.status == MEMBER_GONE) return drop(inbound, "sender left")
        // The envelope must have been verified with the keys the admin vouched for.
        if (inbound.senderFingerprint != entry.fingerprint) return drop(inbound, "sender's keys aren't the vouched ones")
        if (inbound.newSenderKeys != null) memberKeys.adopt(inbound.newSenderKeys, entry.name)
        if (member.keyState != GroupKeyState.OK.wire) groupDao.setKeyState(group.id, inbound.sender, GroupKeyState.OK.wire)
        when (packet.kind) {
            GroupPacket.KIND_MSG -> storeMessage(group, packet, inbound)
            GroupPacket.KIND_REACT -> applyReaction(group, packet, inbound)
        }
    }

    private suspend fun storeMessage(group: GroupEntity, packet: GroupPacket, inbound: Inbound) {
        val me = env.me() ?: return
        val rowId = GroupIds.rowId(group.id, packet.sender, packet.id)
        // A copy we have (a retry, or the relay delivering twice): once is enough.
        if (messageDao.getById(rowId) != null) return
        val body = packet.body ?: return
        val media = MediaPayload.decode(body)
        val file = if (media == null) FilePayload.decode(body) else null
        val text = when {
            media != null -> media.caption
            file != null -> file.caption
            UnknownPayload.isUnknown(body) -> env.unknownPayloadNote()
            else -> body
        }
        val mediaKey = media?.let { mediaStore.save(rowId, it.bytes) }
        val now = env.now()
        val reply = packet.replyTo
        val entity = MessageEntity(
            id = rowId,
            conversationId = group.id,
            senderId = packet.sender,
            recipientId = me,
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = text,
            state = MessageState.DELIVERED.name,
            timestamp = inbound.timestamp,
            expiresAt = group.timerMs.takeIf { it > 0 }?.let { now + it },
            isOutgoing = false,
            replyToId = reply?.let { GroupIds.rowId(group.id, it.sender, it.id) },
            replyQuote = reply?.let { packet.replyQuote.orEmpty() },
            replyToOwn = reply?.sender == me,
            mediaKind = media?.media?.kind?.wire ?: file?.let { MediaKind.FILE.wire },
            mediaMime = media?.media?.mime ?: file?.mime,
            mediaKey = mediaKey ?: file?.key,
            mediaDurationMs = media?.media?.durationMs ?: file?.durationMs ?: 0,
            mediaWidth = media?.media?.width ?: 0,
            mediaHeight = media?.media?.height ?: 0,
            mediaName = file?.name,
            mediaSize = file?.size ?: media?.hd?.size ?: 0,
            mediaState = if (file != null || media?.hd != null) FileState.REMOTE.wire else null,
            attachmentId = file?.id?.ifEmpty { null } ?: media?.hd?.id,
            hdKey = media?.hd?.key,
            thumbnail = file?.thumbnail,
            groupId = group.id,
            groupMessageId = packet.id,
            relayId = inbound.relayId,
        )
        messageDao.insert(entity)
        val senderName = nameOf(group.id, packet.sender)
        val active = activeGroupId == group.id
        groupDao.updatePreview(group.id, previewWithSender(entity, senderName), inbound.timestamp, if (active) 0 else 1)
        if (!active) env.notifyMessage(group, packet.sender, senderName, env.preview(entity), isReaction = false, names = memberNames(group.id))
    }

    private suspend fun applyReaction(group: GroupEntity, packet: GroupPacket, inbound: Inbound) {
        val me = env.me() ?: return
        val target = packet.target ?: return
        val targetRow = messageDao.getById(GroupIds.rowId(group.id, target.sender, target.id)) ?: return
        if (targetRow.groupId != group.id) return
        val emoji = packet.emoji.orEmpty()
        if (emoji.isEmpty()) {
            groupDao.deleteReaction(targetRow.id, packet.sender)
            return
        }
        groupDao.upsertReaction(GroupReactionEntity(targetRow.id, packet.sender, emoji))
        // A reaction to the user's own message is notified, as in a 1:1 chat.
        if (targetRow.senderId == me && activeGroupId != group.id) {
            val name = nameOf(group.id, packet.sender)
            env.notifyMessage(group, packet.sender, name, env.reactionText(emoji, env.preview(targetRow)), isReaction = true, names = memberNames(group.id))
        }
    }

    // ── Held packets ──────────────────────────────────────────────────────

    private suspend fun hold(packet: GroupPacket, inbound: Inbound) {
        groupDao.hold(
            GroupHeldEntity(
                relayId = inbound.relayId,
                groupId = packet.groupId,
                senderId = inbound.sender,
                senderFingerprint = inbound.senderFingerprint,
                packet = inbound.plaintext,
                epoch = packet.epoch,
                receivedAt = env.now(),
            ),
        )
    }

    /** Lets in what waited for the roster just taken. Call with the group's lock held. */
    private suspend fun releaseHeld(groupId: String) {
        val group = groupDao.get(groupId) ?: return
        for (held in groupDao.getHeld(groupId)) {
            if (held.epoch > group.epoch && GroupStatus.fromWire(group.status) == GroupStatus.ACTIVE) continue
            groupDao.deleteHeld(held.relayId)
            val decoded = GroupPacketCodec.decode(held.packet, env.now()) as? GroupPacketCodec.Decoded.Ok ?: continue
            val inbound = Inbound(held.relayId, held.senderId, held.packet, held.receivedAt, held.senderFingerprint, null)
            when (decoded.packet.kind) {
                GroupPacket.KIND_MSG, GroupPacket.KIND_REACT -> onContent(decoded.packet, inbound, fromHeld = true)
                GroupPacket.KIND_ROSTER -> onRoster(decoded.packet, inbound)
            }
        }
    }

    // ── Hooks from the rest of the app ────────────────────────────────────

    /** [userId] deleted their account (a verified account_deleted): they're gone; a group they ran is frozen. */
    suspend fun onAccountDeleted(userId: String) {
        for (membership in groupDao.getMemberships(userId)) {
            lockFor(membership.groupId).withLock {
                groupDao.markGone(membership.groupId, userId)
                val group = groupDao.get(membership.groupId) ?: return@withLock
                if (GroupStatus.fromWire(group.status) != GroupStatus.ACTIVE) return@withLock
                addEvent(group.id, GroupEvent(GroupEvent.LEFT, listOf(userId), listOf(nameOf(group.id, userId))))
                if (group.adminId == userId && !group.frozen) {
                    groupDao.freeze(group.id)
                    addEvent(group.id, GroupEvent(GroupEvent.FROZEN))
                }
                // Ours: they leave the roster.
                if (group.adminId == env.me() && !group.frozen) {
                    val roster = parseRoster(group.roster)
                    if (roster.member(userId) != null) issue(group, roster.copy(members = roster.members.filter { it.userId != userId }), sendNow = false)
                }
            }
        }
    }

    /** [userId]'s keys changed by an endorsed rotation: in groups we run, the roster vouches for the new ones. */
    suspend fun onKeysRotated(userId: String) {
        val me = env.me() ?: return
        for (membership in groupDao.getMemberships(userId)) {
            lockFor(membership.groupId).withLock {
                val group = groupDao.get(membership.groupId) ?: return@withLock
                if (group.adminId != me || group.frozen || GroupStatus.fromWire(group.status) != GroupStatus.ACTIVE) return@withLock
                val fp = memberKeys.pinnedFingerprint(userId) ?: return@withLock
                val roster = parseRoster(group.roster)
                val entry = roster.member(userId) ?: return@withLock
                if (entry.fingerprint == fp) return@withLock
                issue(group, roster.copy(members = roster.members.map { if (it.userId == userId) it.copy(fingerprint = fp) else it }), sendNow = false)
            }
        }
    }

    /** The relay says these copies of ours reached [recipientId]'s phone. */
    suspend fun onDelivered(recipientId: String, relayIds: List<String>) = mark(recipientId, relayIds, COPY_DELIVERED)

    /** [recipientId] read these copies of ours (a signed read receipt). */
    suspend fun onRead(recipientId: String, relayIds: List<String>) = mark(recipientId, relayIds, COPY_READ)

    private suspend fun mark(recipientId: String, relayIds: List<String>, state: String) {
        if (relayIds.isEmpty()) return
        val now = env.now()
        val touched = mutableSetOf<String>()
        relayIds.chunked(500).forEach { chunk ->
            for (copy in groupDao.findByRelayIds(recipientId, chunk)) {
                val rank = COPY_RANK[copy.state] ?: 0
                if (rank >= (COPY_RANK[state] ?: 0)) continue
                groupDao.setState(copy.id, state, now)
                copy.messageId?.let { touched += it }
            }
        }
        touched.forEach { refreshState(it) }
    }

    /** Drops what belongs to messages that are gone, and held packets nobody came for. */
    suspend fun prune() {
        groupDao.clearPreviewsWithoutMessage()
        groupDao.pruneOrphanCopies()
        groupDao.pruneOrphanReactions()
        groupDao.pruneHeld(env.now() - HELD_KEEP_MS)
    }

    override suspend fun markRead(groupId: String) {
        groupDao.clearUnread(groupId)
        env.cancelNotification(groupId)
        receipts.get().markGroupRead(groupId)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun parseRoster(text: String): GroupRoster = json.decodeFromString(GroupRoster.serializer(), text)

    private fun previewWithSender(row: MessageEntity, senderName: String): String {
        val text = env.preview(row)
        return (if (senderName.isEmpty()) text else "$senderName: $text").take(100)
    }

    private fun MessageEntity.toGroupDomain(): Message = Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        plaintext = plaintext,
        state = MessageState.valueOf(state),
        timestamp = Instant.fromEpochMilliseconds(timestamp),
        expiresAt = expiresAt?.let { Instant.fromEpochMilliseconds(it) },
        isOutgoing = isOutgoing,
        replyTo = replyToId?.let { ReplyRef(it, replyQuote.orEmpty(), replyToOwn) },
        media = media(),
    )

    companion object {
        private const val TAG = "GroupRepo"
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"
        const val MEMBER_ACTIVE = "active"
        const val MEMBER_INVITED = "invited"
        const val MEMBER_GONE = "gone"
        const val COPY_QUEUED = "queued"
        const val COPY_SEALED = "sealed"
        const val COPY_SENT = "sent"
        const val COPY_DELIVERED = "delivered"
        const val COPY_READ = "read"
        const val COPY_FAILED = "failed"
        const val COPY_SKIPPED = "skipped"
        /** A copy that can't go: the member's keys differ from the vouched ones. */
        const val ERROR_KEYS = "keys"
        /** A copy waiting for the member's keys (the relay couldn't be asked). */
        const val ERROR_NO_KEYS = "no_keys"
        private val COPY_RANK = mapOf(COPY_QUEUED to 0, COPY_SEALED to 1, COPY_FAILED to 1, COPY_SENT to 2, COPY_DELIVERED to 3, COPY_READ to 4, COPY_SKIPPED to 5)
        private const val SEND_PARALLELISM = 4
        private const val OUTBOX_BATCH = 200
        private const val MAX_CONTROL_ATTEMPTS = 12
        private const val CONTROL_COPY_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        private const val HELD_KEEP_MS = 7L * 24 * 60 * 60 * 1000

        internal fun trustLetter(contact: ContactEntity): String = when {
            contact.verifiedInPerson -> RosterMember.TRUST_IN_PERSON
            contact.verifiedByCall -> RosterMember.TRUST_BY_PHONE
            contact.verified -> RosterMember.TRUST_SHARE_CODE
            else -> RosterMember.TRUST_NONE
        }

        internal fun vouchOf(letter: String): GroupVouch = when (letter) {
            RosterMember.TRUST_IN_PERSON -> GroupVouch.IN_PERSON
            RosterMember.TRUST_BY_PHONE -> GroupVouch.BY_PHONE
            RosterMember.TRUST_SHARE_CODE -> GroupVouch.SHARE_CODE
            else -> GroupVouch.NONE
        }

        internal fun trustOf(contact: ContactEntity): ContactTrust = when {
            contact.verifiedInPerson -> ContactTrust.IN_PERSON
            contact.verifiedByCall -> ContactTrust.BY_PHONE
            else -> ContactTrust.NONE
        }
    }
}
