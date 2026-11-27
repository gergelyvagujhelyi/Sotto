package com.sotto.app.data.repository

import android.content.Context
import com.sotto.app.R
import com.sotto.app.crypto.KeyFingerprint
import com.sotto.app.crypto.KeyManager
import com.sotto.app.data.local.entity.GroupEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.notification.ChatNotificationManager
import com.sotto.app.ui.text.resolve
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** What the group code needs from the app around it: who the user is, words, notifications, scheduling. */
interface GroupEnvironment {
    fun me(): String?
    /** The full fingerprint of this account's public keys (64 hex). */
    fun myFingerprint(): String?
    /** The name the user chose, or "". */
    suspend fun myName(): String
    fun now(): Long
    /** What a chat list or notification says for [entity]. */
    fun preview(entity: MessageEntity): String
    fun reactionText(emoji: String, original: String): String
    /** What stands for a payload from a newer Sotto ("needs a newer Sotto"). */
    fun unknownPayloadNote(): String
    suspend fun notifyMessage(group: GroupEntity, senderId: String, senderName: String, text: String, isReaction: Boolean, names: Map<String, String>)
    suspend fun notifyInvitation(group: GroupEntity, inviterName: String)
    fun cancelNotification(groupId: String)
    /** A message in [group]'s notification was unsent: it's shown again without it, quietly, or goes. */
    suspend fun refreshAfterUnsend(group: GroupEntity, names: Map<String, String>)
    /** What the chat list says for an unsent message: ours ("You unsent a message") or a member's. */
    fun unsentNote(ours: Boolean): String
    /** Sends what waits in the outbox in the background, when there's a network. */
    fun scheduleOutbox()
}

@Singleton
class AndroidGroupEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val identityRepository: Provider<IdentityRepository>,
    private val notificationManager: dagger.Lazy<ChatNotificationManager>,
    private val outbox: com.sotto.app.worker.GroupOutboxScheduler,
) : GroupEnvironment {
    override fun me(): String? = keyManager.getUserId()

    override fun myFingerprint(): String? {
        val (signing, identity) = keyManager.publicIdentityKeys() ?: return null
        return KeyFingerprint.toHex(
            KeyFingerprint.compute(
                identity, signing,
                keyManager.getPqcEncapsulationKey() ?: ByteArray(0),
                keyManager.getMlDsaPublicKey() ?: ByteArray(0),
            ),
        )
    }

    override suspend fun myName(): String = runCatching { identityRepository.get().getMyName() }.getOrNull().orEmpty()

    override fun now(): Long = System.currentTimeMillis()

    override fun preview(entity: MessageEntity): String = entity.preview(context)

    override fun reactionText(emoji: String, original: String): String = reactionNotificationText(emoji, original).resolve(context)

    override fun unknownPayloadNote(): String = context.getString(com.sotto.app.domain.model.UnknownPayload.NOTE)

    override suspend fun notifyMessage(
        group: GroupEntity,
        senderId: String,
        senderName: String,
        text: String,
        isReaction: Boolean,
        names: Map<String, String>,
    ) {
        notificationManager.get().showGroupNotification(group.id, group.title, senderId, senderName, text, isReaction, names)
    }

    override suspend fun notifyInvitation(group: GroupEntity, inviterName: String) {
        notificationManager.get().showGroupNotification(
            group.id, group.title, group.invitedBy ?: "", inviterName,
            context.getString(R.string.group_notification_invited, inviterName, group.title), isReaction = false,
        )
    }

    override fun cancelNotification(groupId: String) = notificationManager.get().cancelNotification(groupId)

    override suspend fun refreshAfterUnsend(group: GroupEntity, names: Map<String, String>) =
        notificationManager.get().refreshGroupAfterUnsend(group.id, group.title, names)

    override fun unsentNote(ours: Boolean): String =
        context.getString(if (ours) R.string.chat_unsent_by_you else R.string.chat_unsent_preview)

    override fun scheduleOutbox() = outbox.schedule()
}
