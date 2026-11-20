package com.sotto.app.ui.contacts

import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * A share code is the first 12 bytes of the key fingerprint in unpadded base64url
 * ([com.sotto.app.crypto.KeyFingerprint.shareCode]): exactly 16 characters, case-sensitive.
 */
internal val SHARE_CODE_PATTERN = Regex("[A-Za-z0-9_-]{16}")

/** The share code a scanned QR code holds, or null when it holds something else. */
internal fun shareCodeFromQr(text: String): String? = text.trim().takeIf { SHARE_CODE_PATTERN.matches(it) }

/**
 * Reads a QR code from a camera frame's luminance (Y) plane, or returns null when the frame has
 * none. [rowStride] is the plane's bytes per row, which can be wider than [width]. The frame's
 * rotation doesn't matter: QR codes decode in any orientation.
 */
internal fun decodeQr(
    luminance: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int = width,
    reader: QRCodeReader = QRCodeReader(),
): String? {
    val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
