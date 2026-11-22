package com.sotto.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import com.sotto.app.domain.model.MediaPayload
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo ready to send: JPEG bytes and its size in pixels. */
class ShrunkPhoto(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Shrinks a picked photo to travel inline (ADR-29): at most [MAX_SIDE] px on the long
 * side, turned upright, and re-encoded as JPEG at the best quality that fits in
 * [MediaPayload.MAX_BYTES]. Re-encoding keeps no metadata, so the photo's location,
 * camera and time stay on the phone.
 */
object PhotoShrinker {
    const val MAX_SIDE = 1280
    /** An HD photo's full-size copy: up to 4096 px, at a quality close to the camera's. */
    const val HD_SIDE = 4096
    val HD_QUALITIES = intArrayOf(90, 85, 80)
    private val QUALITIES = intArrayOf(82, 72, 62, 52, 42)

    /** Null when the file isn't a picture this phone can decode. */
    fun shrink(
        context: Context,
        uri: Uri,
        maxBytes: Int = MediaPayload.MAX_BYTES,
        maxSide: Int = MAX_SIDE,
        qualities: IntArray = QUALITIES,
    ): ShrunkPhoto? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode returns no bitmap, just fills in [bounds].
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        bitmap = upright(bitmap, orientation)

        var side = maxSide
        while (side >= MIN_SIDE) {
            val scaled = scaleDown(bitmap, side)
            for (quality in qualities) {
                val bytes = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                if (bytes.size <= maxBytes) {
                    val photo = ShrunkPhoto(bytes, scaled.width, scaled.height)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    return photo
                }
            }
            if (scaled !== bitmap) scaled.recycle()
            side = (side * 0.8f).roundToInt()
        }
        bitmap.recycle()
        return null
    }

    /** The largest power of two that keeps the long side at or above [target]. */
    internal fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val long = max(width, height)
        while (long / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, side: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= side) return bitmap
        val f = side.toFloat() / long
        return bitmap.scale((bitmap.width * f).roundToInt().coerceAtLeast(1), (bitmap.height * f).roundToInt().coerceAtLeast(1))
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private const val MIN_SIDE = 480
}
