package com.sotto.app.data.repository

import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.media.MediaStore
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.UnsendPayload

/**
 * Empties [row], unsent by its sender (ADR-36), on this phone: what it said, its media and file
 * on disk, and the words of replies quoting it. [state] is what it shows as from now on
 * (UNSENDING or UNSENT). Its time and sender stay, for the note that it was unsent.
 */
internal suspend fun wipeUnsent(messageDao: MessageDao, mediaStore: MediaStore, row: MessageEntity, state: MessageState) {
    require(state.isUnsent) { "Not an unsent state" }
    messageDao.wipeUnsent(row.id, state.name)
    messageDao.setQuotesOf(row.conversationId, row.id, UnsendPayload.UNSENT_QUOTE)
    if (row.mediaKind != null || row.hdKey != null) {
        // A voice message or photo, a file, or a photo's HD copy: the same names the files have.
        runCatching { mediaStore.delete(row.id) }
        runCatching { mediaStore.delete(row.id + MediaStore.HD_SUFFIX) }
    }
}

/** The states a received message can be unsent from: one that arrived and was read (or not yet). */
internal fun receivedUnsendable(state: String): Boolean =
    state == MessageState.DELIVERED.name || state == MessageState.SEEN.name

/** The states our own message can be unsent from: out of our hands, or failed (it may be on the relay). */
internal fun ownUnsendable(state: String): Boolean =
    state == MessageState.SENT.name || state == MessageState.DELIVERED.name ||
        state == MessageState.SEEN.name || state == MessageState.FAILED.name
