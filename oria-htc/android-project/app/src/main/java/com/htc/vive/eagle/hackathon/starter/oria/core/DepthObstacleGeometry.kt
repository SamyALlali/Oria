package com.htc.vive.eagle.hackathon.starter.oria.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DepthPlaneDiagnostic(
    val status: String, val reason: String,
    val a: Double? = null, val b: Double? = null, val c: Double? = null,
    val inlierFraction: Double? = null, val roiMatchingFraction: Double? = null,
    val dominantPlanarView: Boolean? = null,
)

data class DepthGeometryResult(
    val zones: List<DepthZoneEvidence>?,
    val reason: String?,
    val candidateMask: BooleanArray?,
    val candidatePixels: Int?,
    val rawCandidatePixels: Int?,
    val referenceRemovedPixels: Int?,
    val reference: DepthPlaneDiagnostic,
    val qualityUsable: Boolean,
    val qualityReason: String?,
    val version: String = DepthObstacleGeometry.VERSION,
)

/** Relative occupancy only: no categories, YOLO, gravity, metric distance or free-path claim.
 * Kotlin uses a documented xorshift32 sampler, not NumPy PCG64. Cross-runtime equivalence
 * must be measured on fixtures; the version deliberately differs from the Lab sampler.
 */
object DepthObstacleGeometry {
    const val SIZE = 128
    const val VERSION = "relative-depth-occupancy-kotlin-xorshift32-v1"
    private const val PIXELS = SIZE * SIZE
    private const val SEED = 20260927
    private const val INLIER_TOLERANCE = .035

    fun compute(values: FloatArray?, qualityUsable: Boolean = true,
                qualityReason: String? = null): DepthGeometryResult {
        fun unavailable(reason: String) = DepthGeometryResult(null, reason, null, null, null, null,
            DepthPlaneDiagnostic("unavailable", reason), qualityUsable, qualityReason)
        if (values == null) return unavailable("relative_depth_unavailable")
        if (values.size != PIXELS || values.any { !it.isFinite() || it !in 0f..1f }) {
            return unavailable("invalid_relative_depth_values")
        }
        if (values.maxOrNull()!! - values.minOrNull()!! <= 1e-6) return unavailable("degenerate_relative_depth")
        val raw = BooleanArray(PIXELS)
        val roi = BooleanArray(PIXELS)
        var roiCount = 0
        for (i in values.indices) {
            val x = x(i); val y = y(i)
            roi[i] = x >= .08 && x < .92 && y >= .18 && y < .72
            if (roi[i]) { roiCount++; raw[i] = values[i].toDouble() >= .65 }
        }
        val minimum = max(1, ceil(roiCount * .025).toInt())
        val rawKept = components(raw, minimum)
        val (diagnostic, plane) = referencePlane(values)
        val matching = BooleanArray(PIXELS)
        if (plane != null) for (i in values.indices) {
            matching[i] = abs(values[i] - (plane[0] * x(i) + plane[1] * y(i) + plane[2])) <= INLIER_TOLERANCE
        }
        val candidate = components(BooleanArray(PIXELS) { raw[it] && !matching[it] }, minimum)
        val matchingFraction = if (plane == null) null else values.indices.count { matching[it] && roi[it] }.toDouble() / roiCount
        val finalDiagnostic = diagnostic.copy(roiMatchingFraction = matchingFraction,
            dominantPlanarView = matchingFraction?.let { it >= .85 })
        val zones = RgbZone.entries.map { zone ->
            val members = values.indices.filter { roi[it] && zone(it) == zone }
            val retained = members.filter { candidate[it] }
            DepthZoneEvidence(zone, retained.size.toFloat() / members.size,
                if (retained.isEmpty()) 0f else median(retained.map { values[it].toDouble() }).toFloat())
        }
        return DepthGeometryResult(zones, null, candidate, candidate.count { it }, rawKept.count { it },
            values.indices.count { raw[it] && matching[it] }, finalDiagnostic, qualityUsable, qualityReason)
    }

    private fun x(index: Int) = (index % SIZE + .5) / SIZE
    private fun y(index: Int) = (index / SIZE + .5) / SIZE
    private fun zone(index: Int): RgbZone = when {
        x(index) < .39 -> RgbZone.LEFT
        x(index) < .61 -> RgbZone.CENTER
        else -> RgbZone.RIGHT
    }

    private fun components(mask: BooleanArray, minimum: Int): BooleanArray {
        val visited = BooleanArray(PIXELS)
        val result = BooleanArray(PIXELS)
        val queue = IntArray(PIXELS)
        for (seed in mask.indices) {
            if (!mask[seed] || visited[seed]) continue
            var head = 0; var tail = 1
            queue[0] = seed; visited[seed] = true
            while (head < tail) {
                val p = queue[head++]
                fun visit(n: Int) {
                    if (!visited[n] && mask[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (p >= SIZE) visit(p - SIZE)
                if (p < PIXELS - SIZE) visit(p + SIZE)
                if (p % SIZE > 0) visit(p - 1)
                if (p % SIZE < SIZE - 1) visit(p + 1)
            }
            if (tail >= minimum) for (i in 0 until tail) result[queue[i]] = true
        }
        return result
    }

    private class PortableRandom(var state: Int = SEED) {
        fun index(size: Int): Int {
            var next = state
            next = next xor (next shl 13)
            next = next xor (next ushr 17)
            next = next xor (next shl 5)
            state = next
            return ((next.toLong() and 0xffffffffL) % size).toInt()
        }
    }

    private fun referencePlane(values: FloatArray): Pair<DepthPlaneDiagnostic, DoubleArray?> {
        fun absent(reason: String) = DepthPlaneDiagnostic("unavailable", reason) to null
        val region = values.indices.filter { x(it) >= .12 && x(it) < .88 && y(it) >= .62 && y(it) < .94 }
        val usable = region.filter { values[it].toDouble() > .02 && values[it].toDouble() < .98 }
        if (usable.size.toDouble() / region.size < .50) return absent("insufficient_unclipped_support")
        var samples = usable.filter { it % SIZE % 2 == 0 && it / SIZE % 2 == 0 }
        if (samples.size > 1000) samples = List(1000) { samples[it * (samples.size - 1) / 999] }
        if (samples.size < 20) return absent("insufficient_unclipped_support")
        val design = samples.map { doubleArrayOf(x(it), y(it), 1.0) }
        val observed = samples.map { values[it].toDouble() }
        val random = PortableRandom()
        var best: DoubleArray? = null
        var bestCount = -1
        var bestMedian = Double.POSITIVE_INFINITY
        repeat(128) {
            val ids = IntArray(3)
            for (k in ids.indices) {
                var candidate: Int
                do { candidate = random.index(samples.size) } while ((0 until k).any { ids[it] == candidate })
                ids[k] = candidate
            }
            val rows = ids.map { design[it] }.toTypedArray()
            if (rows.maxOf { it[0] } - rows.minOf { it[0] } < .35 ||
                rows.maxOf { it[1] } - rows.minOf { it[1] } < .16) return@repeat
            val coefficients = solve(rows, DoubleArray(3) { observed[ids[it]] }, checkCondition = true) ?: return@repeat
            if (!admissible(coefficients)) return@repeat
            val residuals = design.indices.map { abs(observed[it] - dot(design[it], coefficients)) }
            val count = residuals.count { it <= INLIER_TOLERANCE }
            val med = median(residuals)
            if (count > bestCount || (count == bestCount && med < bestMedian)) {
                best = coefficients; bestCount = count; bestMedian = med
            }
        }
        var coefficients = best ?: return absent("no_supported_affine_hypothesis")
        var inliers = design.indices.filter { abs(observed[it] - dot(design[it], coefficients)) <= INLIER_TOLERANCE }
        repeat(2) {
            if (inliers.size < 3) return absent("no_supported_affine_hypothesis")
            val matrix = Array(3) { DoubleArray(3) }
            val rhs = DoubleArray(3)
            for (j in inliers) for (r in 0..2) {
                rhs[r] += design[j][r] * observed[j]
                for (c in 0..2) matrix[r][c] += design[j][r] * design[j][c]
            }
            coefficients = solve(matrix, rhs) ?: return absent("no_supported_affine_hypothesis")
            inliers = design.indices.filter { abs(observed[it] - dot(design[it], coefficients)) <= INLIER_TOLERANCE }
        }
        val support = inliers.size.toDouble() / samples.size
        fun covered(axis: Int, low: Double, high: Double): Int {
            fun bin(j: Int) = (((design[j][axis] - low) / (high - low) * 4).toInt()).coerceIn(0, 3)
            return (0..3).count { b -> inliers.count { bin(it) == b } >= max(3.0, design.indices.count { bin(it) == b } * .1) }
        }
        if (!admissible(coefficients) || support < .45 || covered(0, .12, .88) < 3 || covered(1, .62, .94) < 3) {
            return absent("no_supported_affine_hypothesis")
        }
        return DepthPlaneDiagnostic("supported", "affine_bottom_band_hypothesis", coefficients[0], coefficients[1],
            coefficients[2], support) to coefficients
    }

    private fun admissible(c: DoubleArray) = c.all { it.isFinite() } && c[1] >= .30 && abs(c[0]) <= .70 * c[1]
    private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted(); val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** Pivoted 3×3 Gaussian elimination. Frobenius condition bound is conservative
     * against NumPy's spectral condition guard; this difference is versioned too. */
    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray, checkCondition: Boolean = false): DoubleArray? {
        val augmented = Array(3) { r -> DoubleArray(7) { c -> when {
            c < 3 -> matrix[r][c]
            c == 3 -> rhs[r]
            c - 4 == r -> 1.0
            else -> 0.0
        } } }
        for (column in 0..2) {
            val pivot = (column..2).maxBy { abs(augmented[it][column]) }
            if (abs(augmented[pivot][column]) < 1e-12) return null
            val swap = augmented[column]; augmented[column] = augmented[pivot]; augmented[pivot] = swap
            val divider = augmented[column][column]
            for (c in 0..6) augmented[column][c] /= divider
            for (r in 0..2) if (r != column) {
                val factor = augmented[r][column]
                for (c in 0..6) augmented[r][c] -= factor * augmented[column][c]
            }
        }
        if (checkCondition) {
            val norm = sqrt(matrix.sumOf { row -> row.sumOf { it * it } })
            val inverseNorm = sqrt(augmented.sumOf { row -> (4..6).sumOf { row[it] * row[it] } })
            if (!norm.isFinite() || !inverseNorm.isFinite() || norm * inverseNorm > 100_000) return null
        }
        return DoubleArray(3) { augmented[it][3] }.takeIf { it.all { v -> v.isFinite() } }
    }
}
