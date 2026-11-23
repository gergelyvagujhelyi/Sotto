package com.sotto.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InPersonCodeTest {

    private val code = InPersonCode("0123456789abcdef", ByteArray(32) { (it * 5).toByte() })

    @Test
    fun `a code reads back as itself, from text and from bytes`() {
        assertEquals(code, InPersonCode.parse(code.encode()))
        assertEquals(code, InPersonCode.parse(code.toBytes()))
        assertEquals(code, InPersonCode.parse("  ${code.encode()}\n"))
        assertTrue(code.encode().startsWith("sotto-verify:1:0123456789abcdef:"))
    }

    @Test
    fun `it carries the whole fingerprint, not a share code's 96 bits`() {
        assertEquals(64, code.fingerprintHex.length)
    }

    @Test
    fun `anything else is no code`() {
        val fp = code.encode().substringAfterLast(':')
        assertNull(InPersonCode.parse("AbCdEfGhIjKlMnOp")) // a share code
        assertNull(InPersonCode.parse("sotto-verify:2:0123456789abcdef:$fp")) // another version
        assertNull(InPersonCode.parse("sotto-verify:1:0123456789ABCDEF:$fp")) // not a user id
        assertNull(InPersonCode.parse("sotto-verify:1:0123456789abcde:$fp"))
        assertNull(InPersonCode.parse("sotto-verify:1:0123456789abcdef:${fp.dropLast(2)}")) // short fingerprint
        assertNull(InPersonCode.parse("sotto-verify:1:0123456789abcdef:not base64!"))
        assertNull(InPersonCode.parse("sotto-verify:1:0123456789abcdef:$fp:extra"))
        assertNull(InPersonCode.parse(""))
    }
}
