package com.sotto.app.data.remote.dto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHealthDtoTest {
    @Test
    fun `a relay that names itself is a Sotto relay`() {
        assertTrue(RelayHealthDto(status = "ok", version = "0.5.17", service = "sotto-relay", inviteRequired = true).isSottoRelay)
    }

    @Test
    fun `relays that still call themselves ChatControll are relays too`() {
        assertTrue(RelayHealthDto(status = "ok", version = "0.5.18", service = "chatcontroll-relay", inviteRequired = true).isSottoRelay)
    }

    @Test
    fun `relays from before 0_5_17 are recognised by their health reply`() {
        assertTrue(RelayHealthDto(status = "ok", version = "0.5.16").isSottoRelay)
    }

    @Test
    fun `other servers are not relays`() {
        assertFalse(RelayHealthDto().isSottoRelay) // nothing we know in the reply
        assertFalse(RelayHealthDto(status = "ok").isSottoRelay)
        assertFalse(RelayHealthDto(status = "ok", version = "latest").isSottoRelay)
        assertFalse(RelayHealthDto(status = "down", version = "1.2.3").isSottoRelay)
    }
}
