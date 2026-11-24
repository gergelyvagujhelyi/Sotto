package com.sotto.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sotto.app.R

@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    // Always dark modules on a light background: theme colours inverted the code
    // in dark mode, which many scanners can't read.
    val foreground = android.graphics.Color.BLACK
    val background = android.graphics.Color.WHITE

    // One pixel per module, drawn without smoothing: a scaled-up 512 px picture blurred
    // every module's edges, and a dense live code was hard to read off the screen.
    val bitmap = remember(content, foreground, background) {
        generateQrBitmap(content, 0, foreground, background)
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        contentDescription = stringResource(R.string.components_share_code_qr),
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
    )
}

private fun generateQrBitmap(
    content: String,
    size: Int,
    foregroundColor: Int,
    backgroundColor: Int,
): Bitmap {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
        com.google.zxing.EncodeHintType.MARGIN to 2,
    )
    // Size 0: the matrix at one pixel per module.
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
        }
    }
    return bitmap
}
