package tech.unyielded.offgrid.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageCompressor {

    private const val MAX_DIMENSION = 1024
    private const val TARGET_QUALITY = 70
    private const val MAX_BYTES = 180_000

    fun compress(context: Context, uri: Uri): ByteArray? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            val scaled = scaleDown(original)
            if (scaled !== original) original.recycle()

            var quality = TARGET_QUALITY
            var result: ByteArray
            do {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                result = baos.toByteArray()
                quality -= 10
            } while (result.size > MAX_BYTES && quality >= 30)

            scaled.recycle()
            result
        } catch (_: Exception) {
            null
        }
    }

    fun decode(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return src
        val ratio = minOf(MAX_DIMENSION.toFloat() / w, MAX_DIMENSION.toFloat() / h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
