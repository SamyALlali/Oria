package com.htc.vive.eagle.hackathon.starter.oria.ml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Fixed image-space contract. No rotation, mirror, crop, category or metric distance. */
object DepthImageProcessing {
    const val INPUT_SIZE = 252
    const val MASK_SIZE = 128
    private val means = floatArrayOf(.485f, .456f, .406f)
    private val stds = floatArrayOf(.229f, .224f, .225f)
    private const val PRECISION = 1 shl 22
    private data class Filter(val start: Int, val weights: DoubleArray) {
        val integers = IntArray(weights.size) { i ->
            (weights[i] * PRECISION + if (weights[i] < 0) -.5 else .5).toInt()
        }
    }

    // Separable antialiased pixel-centre convolution, Catmull-Rom cubic (a=-.5).
    // Matches Pillow's documented filter and RGB8 intermediate rounding, unlike Android bilinear resize.
    // Reference: python-pillow/Pillow src/libImaging/Resample.c; differential fixtures are authoritative.
    private data class FilterKey(val source: Int, val target: Int, val cubic: Boolean)
    // Coefficients are immutable after construction. A synchronized LRU bounds retention
    // across changing camera dimensions and permits independent detector/test workers.
    private val filterCache = LinkedHashMap<FilterKey, Array<Filter>>(16, .75f, true)
    private fun filters(source: Int, target: Int, cubic: Boolean): Array<Filter> = synchronized(filterCache) {
        val key = FilterKey(source, target, cubic)
        filterCache[key] ?: createFilters(source, target, cubic).also { value ->
            filterCache[key] = value
            if (filterCache.size > 16) {
                val iterator = filterCache.entries.iterator()
                iterator.next(); iterator.remove()
            }
        }
    }

    private fun createFilters(source: Int, target: Int, cubic: Boolean): Array<Filter> {
        val scale = source.toDouble() / target
        val filterScale = max(1.0, scale)
        val support = (if (cubic) 2.0 else 1.0) * filterScale
        return Array(target) { out ->
            val center = (out + .5) * scale
            val start = max(0, (center - support + .5).toInt())
            val end = min(source, (center + support + .5).toInt())
            val weights = DoubleArray(end - start) { k ->
                val x = abs((k + start - center + .5) / filterScale)
                if (!cubic) max(0.0, 1 - x)
                else if (x < 1) (1.5 * x - 2.5) * x * x + 1
                else if (x < 2) (((x - 5) * x + 8) * x - 4) * -.5
                else 0.0
            }
            val total = weights.sum()
            for (i in weights.indices) weights[i] /= total
            Filter(start, weights)
        }
    }

    private fun validSize(w: Int, h: Int) {
        require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16_777_216) { "Invalid source dimensions" }
    }

    fun resizedRgb(argb: IntArray, width: Int, height: Int, size: Int = INPUT_SIZE): IntArray {
        validSize(width, height)
        require(argb.size == width * height && size in 1..INPUT_SIZE)
        return resize8(argb, width, height, size, cubic = true, rgb = true)
    }

    private fun resize8(source: IntArray, width: Int, height: Int, size: Int, cubic: Boolean, rgb: Boolean): IntArray {
        val fx = filters(width, size, cubic)
        val fy = filters(height, size, cubic)
        val horizontal = if (width == size) source else IntArray(size * height).also { out ->
            for (y in 0 until height) for (x in 0 until size) {
                val filter = fx[x]
                var r = PRECISION / 2; var g = PRECISION / 2; var b = PRECISION / 2
                for (k in filter.integers.indices) {
                    val p = source[y * width + filter.start + k]
                    val a = filter.integers[k]
                    if (rgb) { r += ((p ushr 16) and 255) * a; g += ((p ushr 8) and 255) * a; b += (p and 255) * a }
                    else r += p * a
                }
                val red = (r shr 22).coerceIn(0, 255)
                out[y * size + x] = if (rgb) (0xff shl 24) or (red shl 16) or
                    ((g shr 22).coerceIn(0, 255) shl 8) or (b shr 22).coerceIn(0, 255) else red
            }
        }
        if (height == size) return horizontal.copyOf()
        return IntArray(size * size).also { out ->
            for (y in 0 until size) for (x in 0 until size) {
                val filter = fy[y]
                var r = PRECISION / 2; var g = PRECISION / 2; var b = PRECISION / 2
                for (k in filter.integers.indices) {
                    val p = horizontal[(filter.start + k) * size + x]
                    val a = filter.integers[k]
                    if (rgb) { r += ((p ushr 16) and 255) * a; g += ((p ushr 8) and 255) * a; b += (p and 255) * a }
                    else r += p * a
                }
                val red = (r shr 22).coerceIn(0, 255)
                out[y * size + x] = if (rgb) (0xff shl 24) or (red shl 16) or
                    ((g shr 22).coerceIn(0, 255) shl 8) or (b shr 22).coerceIn(0, 255) else red
            }
        }
    }

    fun preprocess(argb: IntArray, width: Int, height: Int): FloatArray {
        val resized = resizedRgb(argb, width, height)
        val area = INPUT_SIZE * INPUT_SIZE
        return FloatArray(area * 3).also { result ->
            for (i in resized.indices) for (c in 0..2) {
                val channel = (resized[i] ushr (16 - c * 8)) and 255
                result[c * area + i] = (channel / 255f - means[c]) / stds[c]
            }
        }
    }

    fun compact(raw: FloatArray, width: Int = INPUT_SIZE, height: Int = INPUT_SIZE): FloatArray {
        require(width in 1..INPUT_SIZE && height in 1..INPUT_SIZE && raw.size == width * height)
        require(raw.all { it.isFinite() }) { "Non-finite raw depth" }
        val fx = filters(width, MASK_SIZE, false)
        val fy = filters(height, MASK_SIZE, false)
        val horizontal = if (width == MASK_SIZE) raw else FloatArray(MASK_SIZE * height).also { out ->
            for (y in 0 until height) for (x in 0 until MASK_SIZE) {
                val f = fx[x]; var sum = 0.0
                for (k in f.weights.indices) sum += raw[y * width + f.start + k] * f.weights[k]
                out[y * MASK_SIZE + x] = sum.toFloat()
            }
        }
        if (height == MASK_SIZE) return horizontal.copyOf()
        return FloatArray(MASK_SIZE * MASK_SIZE).also { out ->
            for (y in 0 until MASK_SIZE) for (x in 0 until MASK_SIZE) {
                val f = fy[y]; var sum = 0.0
                for (k in f.weights.indices) sum += horizontal[(f.start + k) * MASK_SIZE + x] * f.weights[k]
                out[y * MASK_SIZE + x] = sum.toFloat()
            }
        }
    }

    fun percentile(sorted: FloatArray, p: Double): Double {
        require(sorted.isNotEmpty() && p in 0.0..100.0)
        val index = (sorted.size - 1) * p / 100
        val lower = index.toInt(); val upper = min(sorted.lastIndex, lower + 1)
        return sorted[lower].toDouble() + (sorted[upper].toDouble() - sorted[lower]) * (index - lower)
    }

    data class Normalized(val values: FloatArray, val available: Boolean, val reason: String?, val p02: Double, val p98: Double)
    fun normalize(compact: FloatArray): Normalized {
        require(compact.size == MASK_SIZE * MASK_SIZE && compact.all { it.isFinite() })
        val sorted = compact.sortedArray()
        val low = percentile(sorted, 2.0); val high = percentile(sorted, 98.0)
        if (high - low <= max(1e-6, max(abs(low), abs(high)) * 1e-6))
            return Normalized(FloatArray(0), false, "degenerate_relative_depth", low, high)
        // NumPy float32 array/scalar operations used by the Mac runtime round each stage to float32.
        val a = low.toFloat(); val span = (high - low).toFloat()
        val result = FloatArray(compact.size) { ((compact[it] - a) / span).coerceIn(0f, 1f) }
        require(result.all { it.isFinite() })
        return Normalized(result, true, null, low, high)
    }

    data class Quality(val usable: Boolean, val reason: String?, val lumaP95: Double, val darkFraction: Double,
                       val meanGradient: Double, val edgeFraction: Double)
    fun quality(argb: IntArray, width: Int, height: Int): Quality {
        validSize(width, height); require(argb.size == width * height)
        val gray = IntArray(argb.size) { i ->
            val p = argb[i]
            ((((p ushr 16) and 255) * 19595 + ((p ushr 8) and 255) * 38470 + (p and 255) * 7471 + 32768) shr 16)
        }
        val luma = resize8(gray, width, height, MASK_SIZE, cubic = false, rgb = false)
        // Luminance is exactly integer8-bit after resize. Histogram rank selection
        // gives the same linear-interpolated P95 as sorting, without two float arrays.
        val histogram = IntArray(256)
        for (value in luma) histogram[value]++
        val rank = (luma.size - 1) * 95.0 / 100
        val lowRank = rank.toInt()
        val highRank = min(luma.lastIndex, lowRank + 1)
        var cumulative = 0
        var lowValue = -1
        var highValue = 255
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (lowValue < 0 && cumulative > lowRank) lowValue = value
            if (cumulative > highRank) { highValue = value; break }
        }
        val p95 = lowValue.toDouble() + (highValue - lowValue) * (rank - lowRank)
        val dark = (0..8).sumOf { histogram[it] }.toDouble() / luma.size
        var gradientSum = 0.0; var edges = 0
        for (y in 0 until MASK_SIZE - 1) for (x in 0 until MASK_SIZE - 1) {
            val i = y * MASK_SIZE + x
            val gradient = (abs(luma[i + 1] - luma[i]) + abs(luma[i + MASK_SIZE] - luma[i])) * .5
            gradientSum += gradient
            if (gradient >= 8) edges++
        }
        val area = (MASK_SIZE - 1) * (MASK_SIZE - 1)
        val mean = gradientSum / area; val edgeFraction = edges.toDouble() / area
        val reason = if (p95 < 24 || dark >= .90) "low_light"
            else if (mean <= 1.5 && edgeFraction <= .01) "low_texture" else null
        return Quality(reason == null, reason, p95, dark, mean, edgeFraction)
    }
}
