package com.sotto.app.nfc

import com.sotto.app.crypto.InPersonCode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InPersonNfcTest {

    private val mine = InPersonCode("aaaaaaaaaaaaaaaa", ByteArray(32) { 1 })
    private val theirs = InPersonCode("bbbbbbbbbbbbbbbb", ByteArray(32) { 2 })
    private val ok = byteArrayOf(0x90.toByte(), 0x00)

    @After
    fun tearDown() = InPersonNfc.stop()

    @Test
    fun `with no verify screen open, the card isn't there`() {
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), InPersonNfc.respond(InPersonNfc.selectCommand()))
        val exchange = InPersonNfc.respond(InPersonNfc.exchangeCommand(theirs))
        assertArrayEquals(byteArrayOf(0x69, 0x85.toByte()), exchange)
    }

    @Test
    fun `an open verify screen answers the select`() {
        InPersonNfc.start(mine)
        assertArrayEquals(ok, InPersonNfc.respond(InPersonNfc.selectCommand()))
    }

    @Test
    fun `an exchange swaps the codes both ways`() = runBlocking {
        InPersonNfc.start(mine)
        val got = async { InPersonNfc.received.first() }
        yield() // let the collector subscribe before the card answers
        val answer = InPersonNfc.respond(InPersonNfc.exchangeCommand(theirs))
        assertArrayEquals(ok, answer.copyOfRange(answer.size - 2, answer.size))
        assertEquals(mine, InPersonCode.parse(answer.copyOfRange(0, answer.size - 2)))
        assertEquals(theirs, got.await())
    }

    @Test
    fun `other apps' commands and other AIDs get no answer`() {
        InPersonNfc.start(mine)
        val ndefSelect = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00)
        assertArrayEquals(byteArrayOf(0x6D, 0x00), InPersonNfc.respond(ndefSelect))
        assertArrayEquals(byteArrayOf(0x6D, 0x00), InPersonNfc.respond(byteArrayOf(0x00)))
    }

    @Test
    fun `a truncated exchange is refused`() {
        InPersonNfc.start(mine)
        val cut = InPersonNfc.exchangeCommand(theirs).copyOf(20)
        assertArrayEquals(byteArrayOf(0x69, 0x85.toByte()), InPersonNfc.respond(cut))
    }
}
