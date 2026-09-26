package com.htc.vive.eagle.hackathon.starter.oria.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaFormat
import kotlin.math.max
import kotlin.math.roundToInt

internal object YuvPixels {
    fun argb(y: Int, u: Int, v: Int, fullRange: Boolean, bt709: Boolean): Int {
        val yy = if (fullRange) y.toFloat() else (y - 16) * (255f / 219f)
        val uu = (u - 128) * if (fullRange) 1f else (255f / 224f)
        val vv = (v - 128) * if (fullRange) 1f else (255f / 224f)
        val r = yy + (if (bt709) 1.5748f else 1.402f) * vv
        val g = yy - (if (bt709) .187324f else .344136f) * uu - (if (bt709) .468124f else .714136f) * vv
        val b = yy + (if (bt709) 1.8556f else 1.772f) * uu
        return (0xff shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
    }

    private class Coefficients(full: Boolean, bt709: Boolean) {
        val y = FloatArray(256) { if (full) it.toFloat() else (it - 16) * (255f / 219f) }
        private val chroma = FloatArray(256) { (it - 128) * if (full) 1f else (255f / 224f) }
        val rv = FloatArray(256) { chroma[it] * if (bt709) 1.5748f else 1.402f }
        val gu = FloatArray(256) { chroma[it] * if (bt709) .187324f else .344136f }
        val gv = FloatArray(256) { chroma[it] * if (bt709) .468124f else .714136f }
        val bu = FloatArray(256) { chroma[it] * if (bt709) 1.8556f else 1.772f }

        fun pixel(luma: Int, u: Int, v: Int): Int {
            val yy = y[luma]
            return (0xff shl 24) or ((yy + rv[v]).roundToInt().coerceIn(0, 255) shl 16) or
                ((yy - gu[u] - gv[v]).roundToInt().coerceIn(0, 255) shl 8) or
                (yy + bu[u]).roundToInt().coerceIn(0, 255)
        }
    }
    private val matrices = arrayOf(Coefficients(false, false), Coefficients(false, true), Coefficients(true, false), Coefficients(true, true))

    internal fun cachedArgb(y: Int, u: Int, v: Int, fullRange: Boolean, bt709: Boolean): Int =
        matrices[(if (fullRange) 2 else 0) + (if (bt709) 1 else 0)].pixel(y, u, v)

    fun toBitmap(image: Image, format: MediaFormat, maxSide: Int, rotation: Int, mirrored: Boolean): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) { "Unsupported decoded image format: ${image.format}" }
        // Android's Image.getPlanes() may clone its array: obtain it once, never once per pixel.
        val planes = image.planes
        require(planes.size == 3)
        val crop = image.cropRect
        val scale = minOf(1f, maxSide.toFloat() / max(crop.width(), crop.height()))
        val width = max(1, (crop.width() * scale).roundToInt())
        val height = max(1, (crop.height() * scale).roundToInt())
        // Bulk-copy the three planes to avoid millions of DirectByteBuffer/JNI reads per image.
        val data = planes.map { p -> p.buffer.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } } }
        val yData = data[0]; val uData = data[1]; val vData = data[2]
        val yRowStride = planes[0].rowStride; val uRowStride = planes[1].rowStride; val vRowStride = planes[2].rowStride
        val yPixelStride = planes[0].pixelStride; val uPixelStride = planes[1].pixelStride; val vPixelStride = planes[2].pixelStride
        val fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
            format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        val bt709 = format.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
        val matrix = matrices[(if (fullRange) 2 else 0) + (if (bt709) 1 else 0)]
        val xs = IntArray(width) { crop.left + it * crop.width() / width }
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val y = crop.top + row * crop.height() / height
            val yy = y * yRowStride; val uy = (y / 2) * uRowStride; val vy = (y / 2) * vRowStride
            var destination = row * width
            for (column in 0 until width) {
                val x = xs[column]
                pixels[destination++] = matrix.pixel(
                    yData[yy + x * yPixelStride].toInt() and 255,
                    uData[uy + (x / 2) * uPixelStride].toInt() and 255,
                    vData[vy + (x / 2) * vPixelStride].toInt() and 255)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (rotation == 0 && !mirrored) return bitmap
        val transform = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, width, height, transform, false)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }
}
