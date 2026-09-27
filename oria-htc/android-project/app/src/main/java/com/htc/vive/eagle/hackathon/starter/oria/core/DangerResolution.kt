package com.htc.vive.eagle.hackathon.starter.oria.core

import kotlin.math.max

enum class DangerSource(val wireName: String, val semantic: Boolean) {
    SEMANTIC_CURRENT("semantic_current", true),
    SEMANTIC_VISUAL_PROXY("semantic_visual_proxy", true),
    SEMANTIC_VISUAL_MEMORY("semantic_visual_memory", true),
    SEMANTIC_MEMORY("semantic_memory", true),
    GENERIC_FALLBACK("generic_fallback", false),
    WIDE_FALLBACK("wide_fallback", false),
    LIMITED_SENSORS("limited_sensors", false),
}

enum class DangerLevel(val rank: Int) { NONE(0), LOW(1), MEDIUM(2), HIGH(3) }

enum class DangerCandidateProvenance {
    RGB_CONFIRMED_CURRENT,
    RGB_VISUAL_MEMORY,
    RGB_WITH_RELATIVE_DEPTH,
    MONOCULAR_FRONTAL_RELATIVE,
    MONOCULAR_OBSTACLE_RELATIVE,
    METRIC_SENSOR,
    LIMITED_INPUTS,
    FIXTURE,
}

data class DangerCandidate(
    val id: String,
    val source: DangerSource,
    val level: DangerLevel,
    val score: Float,
    val zone: RgbZone,
    val observedAtMs: Long,
    val provenance: DangerCandidateProvenance,
    val ownerKey: String,
    val category: RgbCategory? = null,
    val metricDistanceMeters: Float? = null,
    val relativeProximity: Float? = null,
    val confidence: Float = 1f,
) {
    init {
        require(id.isNotBlank() && ownerKey.isNotBlank() && observedAtMs >= 0)
        require(score.isFinite() && confidence.isFinite() && confidence in 0f..1f)
        require(metricDistanceMeters == null || metricDistanceMeters.isFinite() && metricDistanceMeters >= 0f)
        require(relativeProximity == null || relativeProximity.isFinite() && relativeProximity in 0f..1f)
    }

    val identityKey: String get() = "${source.wireName}|${category?.name ?: "generic"}|$ownerKey|${zone.name}"
}

data class DangerObstructionEvidence(
    val zone: RgbZone,
    val observedAtMs: Long,
    val confidence: Float,
    val metricNearestDepthMeters: Float? = null,
    val relativeProximity: Float? = null,
    val occupancyRatio: Float? = null,
    val wide: Boolean = false,
) {
    init {
        require(observedAtMs >= 0 && confidence.isFinite() && confidence in 0f..1f)
        require(metricNearestDepthMeters == null || metricNearestDepthMeters.isFinite() && metricNearestDepthMeters >= 0f)
        require(relativeProximity == null || relativeProximity.isFinite() && relativeProximity in 0f..1f)
        require(occupancyRatio == null || occupancyRatio.isFinite() && occupancyRatio in 0f..1f)
    }
    val metric: Boolean get() = metricNearestDepthMeters != null && occupancyRatio != null
}

data class DangerResolutionContext(
    val obstruction: DangerObstructionEvidence?,
    val hasPotentialSemanticContext: Boolean,
    val hasRepeatedVisualSemanticContext: Boolean,
)

enum class DangerRejectionReason { STALE, LOW_CONFIDENCE, SUPERSEDED, AMBIGUOUS_FALLBACK, WALL_OWNERSHIP,
    FOREIGN_SESSION, CLOCK_REVERSED, FUTURE, OUT_OF_ORDER }
enum class DangerInputStatus { ACCEPTED, STOPPED, FOREIGN_SESSION, CLOCK_REVERSED, FUTURE }
enum class DangerResolutionReason {
    NONE, ONLY_CANDIDATE, LIMITED_SENSORS, SEMANTIC, FALLBACK, HIGHEST_SCORE,
    HELD, SAME_OWNER, REPLACED_EXPIRED, REPLACED_STRONGER, REPLACED_HIGHER_LEVEL,
    RELEASED, GENERATION_RESET,
}

data class DangerRejectedCandidate(val candidate: DangerCandidate, val reason: DangerRejectionReason)

data class DangerApproachEvidence(
    val ownerKey: String,
    val trend: OriaDepthTrend,
    val confidence: Float,
    val ageMs: Long,
    val relativeDelta: Float? = null,
    val ttcMs: Long? = null,
) {
    init {
        require(ownerKey.isNotBlank() && confidence in 0f..1f && ageMs >= 0)
        require(relativeDelta?.isFinite() != false && (ttcMs == null || ttcMs >= 0))
    }
}

data class DangerResolutionInput(
    val generation: Long,
    val frameId: Long,
    val observedAtMs: Long,
    val evaluatedAtMs: Long,
    val candidates: List<DangerCandidate>,
    val context: DangerResolutionContext,
    val approach: List<DangerApproachEvidence> = emptyList(),
)

data class DangerResolutionSnapshot(
    val generation: Long,
    val frameId: Long,
    val observedAtMs: Long,
    val evaluatedAtMs: Long,
    val inputs: List<DangerCandidate>,
    val accepted: List<DangerCandidate>,
    val rejected: List<DangerRejectedCandidate>,
    val selectedBeforeStabilization: DangerCandidate?,
    val selected: DangerCandidate?,
    val ownerKey: String?,
    val policyReason: DangerResolutionReason,
    val stabilizationReason: DangerResolutionReason,
    val reason: DangerResolutionReason,
    val approachEnabled: Boolean,
    val approach: List<DangerApproachEvidence>,
    val inputStatus: DangerInputStatus = DangerInputStatus.ACCEPTED,
    /** Only current accepted evidence may appear here. Memory/held expired owners stay diagnostic. */
    val freshSelected: DangerCandidate? = null,
    val stateChanged: Boolean = true,
)

data class DangerResolutionConfig(
    val maxAgeMs: Long = 500,
    val visualMemoryLifetimeMs: Long = 2_850,
    val minimumConfidence: Float = .35f,
    val approachEnabled: Boolean = false,
    val approachMaximumAgeMs: Long = 500,
    val approachScoreBonus: Float = .12f,
    val relativeDepthMaxAgeMs: Long = 1_500,
    val maximumVisualMemories: Int = 128,
) {
    init {
        require(maxAgeMs > 0 && visualMemoryLifetimeMs >= maxAgeMs &&
            minimumConfidence in 0f..1f && approachMaximumAgeMs > 0 && relativeDepthMaxAgeMs > 0)
        require(approachScoreBonus.isFinite() && approachScoreBonus >= 0f && maximumVisualMemories > 0)
    }
}

/** Direct port of the pure Swift arbitration. Metric-only branches require real metric evidence. */
object DangerResolutionPolicy {
    fun shouldPreferLimitedSensors(
        limited: DangerCandidate,
        fallback: DangerCandidate?,
        semantic: DangerCandidate?,
        context: DangerResolutionContext,
        baseLevel: DangerLevel,
    ): Boolean {
        if (semantic != null) return false
        fallback ?: return true
        val obstruction = context.obstruction ?: return false
        val depth = obstruction.metricNearestDepthMeters ?: return false
        val occupancy = obstruction.occupancyRatio ?: return false
        val stronglyGroundLike = fallback.source == DangerSource.WIDE_FALLBACK && depth > 1f && depth < 1.78f &&
            limited.score >= fallback.score + .2f
        if (stronglyGroundLike) return true
        if (baseLevel == DangerLevel.HIGH) return false
        val groundLike = fallback.source == DangerSource.WIDE_FALLBACK && depth > 1f && occupancy < .36f
        if (!groundLike && obstructionLooksWallLikeStrong(obstruction)) return false
        if (fallback.source == DangerSource.GENERIC_FALLBACK && occupancy < .3f) return true
        return fallback.source == DangerSource.WIDE_FALLBACK &&
            (groundLike || occupancy < .24f && depth > 1.02f)
    }

    fun shouldPreferSemantic(semantic: DangerCandidate, fallback: DangerCandidate?,
                             context: DangerResolutionContext): Boolean {
        fallback ?: return true
        val obstruction = context.obstruction
        val wallLike = obstruction?.let(::obstructionLooksWallLikeStrong) == true
        val ambiguous = obstruction?.let { isAmbiguousFallback(fallback, it) } == true
        val zoneConflict = semantic.zone != fallback.zone
        return when (semantic.source) {
            DangerSource.SEMANTIC_CURRENT, DangerSource.SEMANTIC_MEMORY -> {
                if (wallLike && zoneConflict) false
                else if (semantic.metricDistanceMeters?.let { it < 1.55f } == true) true
                else semantic.score + (if (ambiguous) .02f else .18f) >= fallback.score || zoneConflict && ambiguous
            }
            DangerSource.SEMANTIC_VISUAL_PROXY, DangerSource.SEMANTIC_VISUAL_MEMORY -> {
                if (wallLike) false
                else context.hasRepeatedVisualSemanticContext && zoneConflict && ambiguous ||
                    semantic.score >= fallback.score + .22f || ambiguous && semantic.score + .08f >= fallback.score
            }
            else -> false
        }
    }

    fun shouldSuppressAmbiguousFallback(fallback: DangerCandidate, context: DangerResolutionContext): Boolean =
        context.obstruction?.let { isAmbiguousFallback(fallback, it) } == true && context.hasPotentialSemanticContext

    fun shouldPreferFallback(fallback: DangerCandidate, semantic: DangerCandidate?,
                             context: DangerResolutionContext): Boolean {
        semantic ?: return true
        val obstruction = context.obstruction
        if (obstruction != null && obstructionLooksWallLikeStrong(obstruction)) {
            return !semanticCanOverrideWall(semantic, fallback, context)
        }
        return fallback.score >= semantic.score + .45f
    }

    private fun semanticCanOverrideWall(semantic: DangerCandidate, fallback: DangerCandidate,
                                        context: DangerResolutionContext): Boolean {
        val obstruction = context.obstruction ?: return false
        val depth = obstruction.metricNearestDepthMeters ?: return false
        val distance = semantic.metricDistanceMeters ?: return false
        val closeToSurface = distance <= max(1.32f, depth + .16f)
        val closeRange = distance < 1.48f || depth < 1.32f
        val severity = semantic.level.rank >= max(1, fallback.level.rank - 1)
        val score = semantic.score + .2f >= fallback.score
        return when (semantic.source) {
            DangerSource.SEMANTIC_CURRENT, DangerSource.SEMANTIC_MEMORY ->
                semantic.zone == fallback.zone && closeToSurface && closeRange && severity && score
            DangerSource.SEMANTIC_VISUAL_PROXY, DangerSource.SEMANTIC_VISUAL_MEMORY ->
                context.hasRepeatedVisualSemanticContext && closeRange && semantic.score >= fallback.score + .18f
            else -> false
        }
    }

    fun isAmbiguousFallback(fallback: DangerCandidate, obstruction: DangerObstructionEvidence): Boolean {
        if (!obstruction.metric || obstructionLooksWallLikeStrong(obstruction)) return false
        val depth = requireNotNull(obstruction.metricNearestDepthMeters)
        val occupancy = requireNotNull(obstruction.occupancyRatio)
        return if (fallback.source == DangerSource.WIDE_FALLBACK) occupancy < .22f && depth > 1.35f
        else !obstruction.wide && occupancy < .28f
    }

    fun obstructionLooksWallLikeStrong(obstruction: DangerObstructionEvidence): Boolean {
        val depth = obstruction.metricNearestDepthMeters ?: return false
        val occupancy = obstruction.occupancyRatio ?: return false
        return depth < .88f || occupancy > .58f || obstruction.wide && (occupancy > .24f || depth < 1.22f)
    }
}

/** Diagnostic-only resolver. It never reserves or dispatches speech.
 * The controller must reset(videoSessionId) at start and stop() on invalidation.
 * Observations are ordered per producer, so a slower depth result can follow a newer RGB result.
 */
class DangerResolutionEngine(private var config: DangerResolutionConfig = DangerResolutionConfig()) {
    private data class Active(val candidate: DangerCandidate, val confirmedAtMs: Long)
    private var generation: Long? = null
    private var active: Active? = null
    private val visualMemory = linkedMapOf<String, DangerCandidate>()
    private var lastEvaluatedAtMs: Long? = null
    private val lastObservedByProducer = mutableMapOf<String, Long>()

    fun setApproachEnabled(enabled: Boolean) {
        config = config.copy(approachEnabled = enabled)
    }

    fun reset(newGeneration: Long? = null) {
        require(newGeneration == null || newGeneration >= 0)
        generation = newGeneration
        active = null
        visualMemory.clear()
        lastEvaluatedAtMs = null
        lastObservedByProducer.clear()
    }

    fun stop() = reset(null)

    fun resolve(input: DangerResolutionInput): DangerResolutionSnapshot {
        val status = when {
            generation == null -> DangerInputStatus.STOPPED
            input.generation != generation -> DangerInputStatus.FOREIGN_SESSION
            input.evaluatedAtMs < 0 || lastEvaluatedAtMs?.let { input.evaluatedAtMs < it } == true ->
                DangerInputStatus.CLOCK_REVERSED
            input.observedAtMs < 0 || input.observedAtMs > input.evaluatedAtMs -> DangerInputStatus.FUTURE
            else -> DangerInputStatus.ACCEPTED
        }
        if (status != DangerInputStatus.ACCEPTED) return rejectedInput(input, status)
        lastEvaluatedAtMs = input.evaluatedAtMs
        val context = input.context.copy(obstruction = input.context.obstruction?.takeIf {
            val age = input.evaluatedAtMs - it.observedAtMs
            age in 0..(if (it.metric) config.maxAgeMs else config.relativeDepthMaxAgeMs) &&
                it.confidence >= config.minimumConfidence
        })
        val rejected = mutableListOf<DangerRejectedCandidate>()
        visualMemory.entries.removeAll { input.evaluatedAtMs - it.value.observedAtMs > config.visualMemoryLifetimeMs }
        val current = input.candidates.mapNotNull { candidate ->
            val age = input.evaluatedAtMs - candidate.observedAtMs
            val reason = when {
                age < 0 -> DangerRejectionReason.FUTURE
                age > lifetime(candidate) -> DangerRejectionReason.STALE
                lastObservedByProducer[producer(candidate)]?.let { candidate.observedAtMs < it } == true ->
                    DangerRejectionReason.OUT_OF_ORDER
                candidate.confidence < config.minimumConfidence -> DangerRejectionReason.LOW_CONFIDENCE
                else -> null
            }
            if (reason != null) {
                rejected += DangerRejectedCandidate(candidate, reason)
                null
            } else applyApproach(candidate, input.approach)
        }
        // Update after validating the whole batch, making input ordering irrelevant.
        current.filterNot { it.source in MEMORY_SOURCES }.groupBy(::producer).forEach { (producer, values) ->
            lastObservedByProducer[producer] = values.maxOf { it.observedAtMs }
        }
        val liveOwners = input.candidates.mapTo(mutableSetOf()) { it.ownerKey }
        val remembered = visualMemory.values.filter { it.ownerKey !in liveOwners }.map {
            it.copy(id = "memory-${it.ownerKey}", source = DangerSource.SEMANTIC_VISUAL_MEMORY,
                score = it.score - .08f, provenance = DangerCandidateProvenance.RGB_VISUAL_MEMORY)
        }
        // Never turn a refused stale/weak/future observation into accepted memory on a later call.
        current.filter { it.source == DangerSource.SEMANTIC_VISUAL_PROXY }.forEach {
            visualMemory[it.ownerKey] = it
        }
        if (visualMemory.size > config.maximumVisualMemories) {
            visualMemory.entries.sortedWith(compareBy<Map.Entry<String, DangerCandidate>> { it.value.observedAtMs }
                .thenBy { it.key }).take(visualMemory.size - config.maximumVisualMemories)
                .forEach { visualMemory.remove(it.key) }
        }
        val allInputs = input.candidates + remembered
        val accepted = current + remembered
        val semantic = bestSemantic(accepted)
        val fallback = accepted.filter { it.source == DangerSource.GENERIC_FALLBACK || it.source == DangerSource.WIDE_FALLBACK }
            .maxWithOrNull(compareBy<DangerCandidate> { it.score }.thenByDescending { it.id })
        val limited = accepted.filter { it.source == DangerSource.LIMITED_SENSORS }
            .maxWithOrNull(compareBy<DangerCandidate> { it.score }.thenByDescending { it.id })
        val baseLevel = listOfNotNull(fallback, semantic).maxByOrNull { it.level.rank }?.level ?: DangerLevel.NONE
        val pair = when {
            limited != null && DangerResolutionPolicy.shouldPreferLimitedSensors(limited, fallback, semantic,
                context, baseLevel) -> limited to DangerResolutionReason.LIMITED_SENSORS
            semantic != null && DangerResolutionPolicy.shouldPreferSemantic(semantic, fallback, context) ->
                semantic to DangerResolutionReason.SEMANTIC
            fallback != null && DangerResolutionPolicy.shouldSuppressAmbiguousFallback(fallback, context) -> {
                rejected += DangerRejectedCandidate(fallback, DangerRejectionReason.AMBIGUOUS_FALLBACK)
                (semantic ?: limited) to DangerResolutionReason.SEMANTIC
            }
            fallback != null && DangerResolutionPolicy.shouldPreferFallback(fallback, semantic, context) ->
                fallback to DangerResolutionReason.FALLBACK
            else -> accepted.maxWithOrNull(compareBy<DangerCandidate> { it.score }.thenByDescending { it.id }) to
                if (accepted.size == 1) DangerResolutionReason.ONLY_CANDIDATE else DangerResolutionReason.HIGHEST_SCORE
        }
        accepted.filter { it !== pair.first && rejected.none { rejectedItem -> rejectedItem.candidate === it } }
            .forEach {
                val wallOwned = pair.first === fallback && it === semantic &&
                    context.obstruction?.let(DangerResolutionPolicy::obstructionLooksWallLikeStrong) == true
                rejected += DangerRejectedCandidate(it, if (wallOwned) DangerRejectionReason.WALL_OWNERSHIP
                    else DangerRejectionReason.SUPERSEDED)
            }
        val stabilized = stabilize(pair.first, input.evaluatedAtMs)
        val reason = stabilized.second.takeUnless { it == DangerResolutionReason.NONE } ?: pair.second
        val freshSelected = stabilized.first?.takeIf { selected ->
            selected.source !in MEMORY_SOURCES && current.any {
                it.identityKey == selected.identityKey && it.observedAtMs == selected.observedAtMs &&
                    input.evaluatedAtMs - it.observedAtMs in 0..lifetime(it)
            }
        }
        return DangerResolutionSnapshot(input.generation, input.frameId, input.observedAtMs, input.evaluatedAtMs,
            allInputs, accepted, rejected, pair.first, stabilized.first, stabilized.first?.ownerKey,
            pair.second, stabilized.second, reason, config.approachEnabled, input.approach,
            freshSelected = freshSelected)
    }

    private fun rejectedInput(input: DangerResolutionInput, status: DangerInputStatus): DangerResolutionSnapshot {
        val reason = when (status) {
            DangerInputStatus.CLOCK_REVERSED -> DangerRejectionReason.CLOCK_REVERSED
            DangerInputStatus.FUTURE -> DangerRejectionReason.FUTURE
            else -> DangerRejectionReason.FOREIGN_SESSION
        }
        return DangerResolutionSnapshot(generation ?: input.generation, input.frameId, input.observedAtMs,
            input.evaluatedAtMs, input.candidates, emptyList(), input.candidates.map { DangerRejectedCandidate(it, reason) },
            null, active?.candidate, active?.candidate?.ownerKey, DangerResolutionReason.NONE,
            DangerResolutionReason.NONE, DangerResolutionReason.NONE, config.approachEnabled, input.approach,
            inputStatus = status, freshSelected = null, stateChanged = false)
    }

    private fun producer(candidate: DangerCandidate): String = when (candidate.provenance) {
        DangerCandidateProvenance.MONOCULAR_FRONTAL_RELATIVE,
        DangerCandidateProvenance.MONOCULAR_OBSTACLE_RELATIVE -> "relative_depth"
        else -> if (candidate.source.semantic) "semantic_rgb" else candidate.source.wireName
    }

    private fun lifetime(candidate: DangerCandidate): Long = when {
        candidate.source in MEMORY_SOURCES -> config.visualMemoryLifetimeMs
        candidate.provenance in setOf(DangerCandidateProvenance.MONOCULAR_FRONTAL_RELATIVE,
            DangerCandidateProvenance.MONOCULAR_OBSTACLE_RELATIVE) -> config.relativeDepthMaxAgeMs
        else -> config.maxAgeMs
    }

    private fun applyApproach(candidate: DangerCandidate, evidence: List<DangerApproachEvidence>): DangerCandidate {
        if (!config.approachEnabled) return candidate
        val signal = evidence.firstOrNull { it.ownerKey == candidate.ownerKey &&
            it.ageMs <= config.approachMaximumAgeMs && it.confidence >= config.minimumConfidence } ?: return candidate
        if (signal.trend != OriaDepthTrend.APPROACHING) return candidate
        return candidate.copy(score = candidate.score + config.approachScoreBonus * signal.confidence)
    }

    private fun bestSemantic(candidates: List<DangerCandidate>): DangerCandidate? {
        val order = listOf(DangerSource.SEMANTIC_CURRENT, DangerSource.SEMANTIC_MEMORY,
            DangerSource.SEMANTIC_VISUAL_PROXY, DangerSource.SEMANTIC_VISUAL_MEMORY)
        for (source in order) candidates.filter { it.source == source }
            .maxWithOrNull(compareBy<DangerCandidate> { it.score + sourceBoost(it.source) }.thenByDescending { it.id })
            ?.let { return it }
        return null
    }

    private fun sourceBoost(source: DangerSource): Float = when (source) {
        DangerSource.SEMANTIC_CURRENT -> .4f
        DangerSource.SEMANTIC_MEMORY -> .25f
        DangerSource.SEMANTIC_VISUAL_PROXY -> .16f
        DangerSource.SEMANTIC_VISUAL_MEMORY -> .08f
        else -> 0f
    }

    private fun stabilize(incoming: DangerCandidate?, nowMs: Long): Pair<DangerCandidate?, DangerResolutionReason> {
        val expired = active?.let { nowMs - it.candidate.observedAtMs > lifetime(it.candidate) } == true
        if (expired) active = null
        val current = active
        if (incoming == null) {
            if (current != null && nowMs - current.confirmedAtMs <= releaseDuration(current.candidate)) {
                return current.candidate to DangerResolutionReason.HELD
            }
            active = null
            return null to if (current == null && !expired) DangerResolutionReason.NONE else DangerResolutionReason.RELEASED
        }
        if (current == null) {
            active = Active(incoming, nowMs)
            return incoming to DangerResolutionReason.ONLY_CANDIDATE
        }
        if (current.candidate.identityKey == incoming.identityKey) {
            active = Active(incoming, nowMs)
            return incoming to DangerResolutionReason.SAME_OWNER
        }
        val holdExpired = nowMs - current.confirmedAtMs > holdDuration(current.candidate)
        val stronger = incoming.score >= current.candidate.score + replacementThreshold(current.candidate, incoming)
        val higherLevel = incoming.level.rank > current.candidate.level.rank
        val semanticOverride = current.candidate.source == DangerSource.WIDE_FALLBACK && incoming.source.semantic &&
            incoming.score + .12f >= current.candidate.score
        return if (holdExpired || stronger || higherLevel || semanticOverride) {
            active = Active(incoming, nowMs)
            incoming to when {
                holdExpired -> DangerResolutionReason.REPLACED_EXPIRED
                higherLevel -> DangerResolutionReason.REPLACED_HIGHER_LEVEL
                else -> DangerResolutionReason.REPLACED_STRONGER
            }
        } else current.candidate to DangerResolutionReason.HELD
    }

    private fun holdDuration(candidate: DangerCandidate): Long = if (candidate.source == DangerSource.LIMITED_SENSORS) {
        when (candidate.level) { DangerLevel.HIGH -> 400L; DangerLevel.MEDIUM -> 280L; DangerLevel.LOW -> 180L; else -> 0L }
    } else {
        val base = when (candidate.level) { DangerLevel.HIGH -> 1_150L; DangerLevel.MEDIUM -> 950L; DangerLevel.LOW -> 600L; else -> 0L }
        base + if (candidate.source.semantic) 150L else 0L
    }

    private fun releaseDuration(candidate: DangerCandidate): Long = if (candidate.source == DangerSource.LIMITED_SENSORS) {
        when (candidate.level) { DangerLevel.HIGH -> 420L; DangerLevel.MEDIUM -> 300L; DangerLevel.LOW -> 220L; else -> 0L }
    } else {
        val base = when (candidate.level) { DangerLevel.HIGH -> 1_250L; DangerLevel.MEDIUM -> 1_000L; DangerLevel.LOW -> 750L; else -> 0L }
        base + if (candidate.source.semantic) 100L else 0L
    }

    private fun replacementThreshold(current: DangerCandidate, incoming: DangerCandidate): Float = when {
        current.source == DangerSource.LIMITED_SENSORS && incoming.source.semantic -> .04f
        current.source == DangerSource.WIDE_FALLBACK && incoming.source.semantic -> .08f
        current.level == DangerLevel.HIGH && incoming.level.rank < current.level.rank -> .7f
        else -> .28f
    }

    companion object {
        private val MEMORY_SOURCES = setOf(DangerSource.SEMANTIC_MEMORY, DangerSource.SEMANTIC_VISUAL_MEMORY)
    }
}

/** Eagle adapter: relative depth enriches provenance but never becomes a metric Swift branch. */
object OriaDangerAdapter {
    fun input(evaluation: RgbEvaluation, distance: Map<Long, OriaDistanceEvidence>,
              frontal: OriaDistanceEvidence?, evaluatedAtMs: Long,
              approachEnabled: Boolean): DangerResolutionInput {
        val current = evaluation.candidates.map { rgb ->
            val depth = distance[rgb.trackId]?.takeIf { it.available }
            DangerCandidate("rgb-${rgb.trackId}", DangerSource.SEMANTIC_VISUAL_PROXY,
                visualLevel(rgb.priority), rgb.priority, rgb.zone, rgb.observedAtMs,
                if (depth == null) DangerCandidateProvenance.RGB_CONFIRMED_CURRENT
                else DangerCandidateProvenance.RGB_WITH_RELATIVE_DEPTH,
                ownerKey = "track-${rgb.trackId}", category = rgb.category,
                relativeProximity = depth?.relativeProximity,
                confidence = minOf(rgb.detection.confidence, depth?.confidence ?: 1f))
        }
        val generic = frontal?.takeIf { it.available }?.let {
            DangerCandidate("monocular-frontal", DangerSource.GENERIC_FALLBACK,
                relativeLevel(requireNotNull(it.relativeProximity)), requireNotNull(it.relativeProximity),
                RgbZone.CENTER, evaluatedAtMs - it.ageMs, DangerCandidateProvenance.MONOCULAR_FRONTAL_RELATIVE,
                "frontal-corridor", relativeProximity = it.relativeProximity, confidence = it.confidence)
        }
        val approach = if (!approachEnabled) emptyList() else distance.mapNotNull { (trackId, evidence) ->
            if (!evidence.available) null else DangerApproachEvidence("track-$trackId", evidence.trend,
                evidence.confidence, evidence.ageMs)
        }
        val obstruction = frontal?.takeIf { it.available }?.let {
            DangerObstructionEvidence(RgbZone.CENTER, evaluatedAtMs - it.ageMs, it.confidence,
                relativeProximity = it.relativeProximity)
        }
        return DangerResolutionInput(evaluation.sessionId ?: -1L, evaluation.frameId ?: -1L,
            evaluation.candidates.maxOfOrNull { it.observedAtMs } ?: evaluatedAtMs, evaluatedAtMs,
            current + listOfNotNull(generic), DangerResolutionContext(obstruction,
                current.isNotEmpty(), evaluation.tracks.any { it.visibleInLatestFrame && it.confirmed && it.confirmationSamples >= 2 }), approach)
    }

    private fun visualLevel(priority: Float): DangerLevel = when {
        priority >= 2.45f -> DangerLevel.HIGH
        priority >= 1.65f -> DangerLevel.MEDIUM
        else -> DangerLevel.LOW
    }

    private fun relativeLevel(proximity: Float): DangerLevel = when {
        proximity >= .78f -> DangerLevel.HIGH
        proximity >= .5f -> DangerLevel.MEDIUM
        else -> DangerLevel.LOW
    }
}
