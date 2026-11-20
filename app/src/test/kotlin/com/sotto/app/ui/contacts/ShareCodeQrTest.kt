package com.sotto.app.ui.contacts

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareCodeQrTest {

    private val shareCode = "hT7wQ2xLp9Vr4KzN"

    /** A QR code drawn the way a camera frame's luminance plane holds it: one byte per pixel. */
    private fun qrFrame(text: String, size: Int, rowStride: Int = size): ByteArray {
        val modules = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val frame = ByteArray(rowStride * size) { 0x7F }
        for (y in 0 until size) {
            for (x in 0 until size) {
                frame[y * rowStride + x] = if (modules[x, y]) 0 else 0xFF.toByte()
            }
        }
        return frame
    }

    @Test
    fun `reads the share code from a frame`() {
        val text = decodeQr(qrFrame(shareCode, 320), 320, 320)
        assertEquals(shareCode, text)
        assertEquals(shareCode, shareCodeFromQr(text!!))
    }

    @Test
    fun `reads a frame whose rows are padded wider than the image`() {
        val text = decodeQr(qrFrame(shareCode, 320, rowStride = 384), 320, 320, rowStride = 384)
        assertEquals(shareCode, text)
    }

    @Test
    fun `finds nothing in a frame without a QR code`() {
        assertNull(decodeQr(ByteArray(320 * 320) { 0x7F }, 320, 320))
    }

    @Test
    fun `accepts only a 16 character base64url share code`() {
        assertEquals(shareCode, shareCodeFromQr(" $shareCode\n"))
        assertEquals("ab-_CD12ef34GH56", shareCodeFromQr("ab-_CD12ef34GH56"))
        assertNull(shareCodeFromQr("https://example.com/hT7wQ2xLp9Vr4KzN"))
        assertNull(shareCodeFromQr(shareCode.dropLast(1)))
        assertNull(shareCodeFromQr(shareCode + "x"))
        assertNull(shareCodeFromQr("hT7wQ2xLp9Vr4K+N"))
        assertNull(shareCodeFromQr(""))
    }

    @Test
    fun `a QR code with other text is not taken for a share code`() {
        val text = decodeQr(qrFrame("WIFI:S:home;T:WPA;P:secret;;", 320), 320, 320)
        assertEquals("WIFI:S:home;T:WPA;P:secret;;", text)
        assertNull(shareCodeFromQr(text!!))
    }
}
