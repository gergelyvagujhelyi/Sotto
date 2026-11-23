package com.sotto.app.ui.chat

import com.sotto.app.domain.model.FileState
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageMedia
import com.sotto.app.domain.model.MessageState
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** A file's ticks say where the file is, not only its message. */
class FileTicksTest {
    private fun file(state: MessageState, file: FileState?, outgoing: Boolean = true, kind: MediaKind = MediaKind.FILE) = Message(
        id = "m", conversationId = "c", senderId = "me", recipientId = "peer", plaintext = "",
        state = state, timestamp = Instant.fromEpochMilliseconds(0), isOutgoing = outgoing,
        media = MessageMedia(kind, "video/mp4", fileState = file),
    )

    @Test
    fun `a file still only on this phone shows the clock, even when its message is delivered`() {
        assertEquals(MessageState.SENDING, file(MessageState.DELIVERED, FileState.DIRECT).shownState)
        assertEquals(MessageState.SENDING, file(MessageState.SEEN, FileState.UPLOADING).shownState)
        assertEquals(MessageState.SENDING, file(MessageState.SENT, FileState.DIRECT).shownState)
    }

    @Test
    fun `a file in the store shows one tick, and delivered only once it's on their phone`() {
        assertEquals(MessageState.SENT, file(MessageState.DELIVERED, FileState.SENT).shownState)
        assertEquals(MessageState.SENT, file(MessageState.SEEN, FileState.SENT).shownState)
        assertEquals(MessageState.DELIVERED, file(MessageState.DELIVERED, FileState.RECEIVED).shownState)
        assertEquals(MessageState.SEEN, file(MessageState.SEEN, FileState.RECEIVED).shownState)
    }

    @Test
    fun `a file that couldn't follow its message shows as failed`() {
        assertEquals(MessageState.FAILED, file(MessageState.DELIVERED, FileState.UNSENT).shownState)
    }

    @Test
    fun `the message's own state wins while it's sending or failed, and other media keep theirs`() {
        assertEquals(MessageState.SENDING, file(MessageState.SENDING, FileState.UPLOADING).shownState)
        assertEquals(MessageState.FAILED, file(MessageState.FAILED, FileState.UPLOADING).shownState)
        assertEquals(MessageState.DELIVERED, file(MessageState.DELIVERED, null, kind = MediaKind.PHOTO).shownState)
        assertEquals(MessageState.DELIVERED, file(MessageState.DELIVERED, FileState.REMOTE, outgoing = false).shownState)
    }
}
