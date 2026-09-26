package com.htc.vive.eagle.hackathon.starter.oria.ml

import android.graphics.Bitmap
import kotlin.math.floor

/** Deterministic half-pixel bilinear RGB uint8 resize, centered padding 114, RGB / 255 NCHW. */
object LetterboxPreprocessor {
    fun transform(bitmap: Bitmap): LetterboxTransform = LetterboxTransform(bitmap.width, bitmap.height)

    fun preprocess(bitmap: Bitmap, destination: FloatArray): LetterboxTransform {
        require(!bitmap.isRecycled)
        val t = transform(bitmap)
        val plane = t.size * t.size
        require(destination.size == plane * 3)
        destination.fill(114f / 255f)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val x0 = IntArray(t.resizedWidth)
        val x1 = IntArray(t.resizedWidth)
        val wx = DoubleArray(t.resizedWidth)
        for (x in 0 until t.resizedWidth) {
            val sourceX = ((x + 0.5) * bitmap.width / t.resizedWidth - 0.5).coerceIn(0.0, bitmap.width - 1.0)
            x0[x] = floor(sourceX).toInt()
            x1[x] = (x0[x] + 1).coerceAtMost(bitmap.width - 1)
            wx[x] = sourceX - x0[x]
        }
        for (y in 0 until t.resizedHeight) {
            val sourceY = ((y + 0.5) * bitmap.height / t.resizedHeight - 0.5).coerceIn(0.0, bitmap.height - 1.0)
            val y0 = floor(sourceY).toInt()
            val y1 = (y0 + 1).coerceAtMost(bitmap.height - 1)
            val wy = sourceY - y0
            for (x in 0 until t.resizedWidth) {
                val p00 = pixels[y0 * bitmap.width + x0[x]]
                val p01 = pixels[y0 * bitmap.width + x1[x]]
                val p10 = pixels[y1 * bitmap.width + x0[x]]
                val p11 = pixels[y1 * bitmap.width + x1[x]]
                val destinationIndex = (y + t.top) * t.size + x + t.left
                for (channel in 0..2) {
                    val shift = 16 - 8 * channel
                    val a = ((p00 ushr shift) and 255) * (1 - wx[x]) + ((p01 ushr shift) and 255) * wx[x]
                    val b = ((p10 ushr shift) and 255) * (1 - wx[x]) + ((p11 ushr shift) and 255) * wx[x]
                    val value = floor(a * (1 - wy) + b * wy + 0.5).toInt().coerceIn(0, 255)
                    destination[channel * plane + destinationIndex] = value / 255f
                }
            }
        }
        return t
    }
}
