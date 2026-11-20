package com.sotto.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptManagerTest {
    @Test
    fun `relay message id matches the server's derivation`() {
        // server/app/routers/messages.py:
        //   hashlib.sha256(f"{sender_id}:{client_message_id}".encode()).hexdigest()[:32]
        assertEquals(
            "a0eaf134b6ab810784b7bac3135b9940",
            ReceiptManager.relayMessageId("a1b2c3d4e5f60718", "6f1c0d1e-2b3a-4c5d-8e9f-001122334455"),
        )
    }
}
