package com.htc.vive.eagle.hackathon.starter.oria.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DangerResolutionSwiftFixturesTest {
    private fun fields(line: String): Map<String, String> =
        Regex("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|true|false|-?[0-9]+(?:\\.[0-9]+)?)")
            .findAll(line).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    @Test fun sameResolutionFixturesAsExtractedSwiftPolicy() {
        val fixtures = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "fixtures/danger_resolution_cases.jsonl") }.firstOrNull { it.isFile }
            ?: error("Cannot find danger_resolution_cases.jsonl")
        val results = mutableListOf<String>()
        fixtures.readLines().filter { it.isNotBlank() }.forEach { line ->
            val f = fields(line)
            fun float(name: String) = f.getValue(name).toFloat()
            fun bool(name: String) = f.getValue(name).toBoolean()
            fun source(name: String) = DangerSource.entries.first { it.wireName == f.getValue(name) }
            fun zone(name: String) = RgbZone.valueOf(f.getValue(name).uppercase())
            val obstruction = DangerObstructionEvidence(RgbZone.CENTER, 0, 1f, float("depth"),
                occupancyRatio = float("occupancy"), wide = bool("wide"))
            val actual = when (f.getValue("type")) {
                "wallLike" -> DangerResolutionPolicy.obstructionLooksWallLikeStrong(obstruction)
                "ambiguous" -> DangerResolutionPolicy.isAmbiguousFallback(
                    DangerCandidate("fallback", source("source"), DangerLevel.MEDIUM, 1f, RgbZone.CENTER,
                        0, DangerCandidateProvenance.FIXTURE, "fallback", metricDistanceMeters = float("depth")),
                    obstruction)
                "preferSemantic" -> {
                    val semantic = DangerCandidate("semantic", source("semanticSource"), DangerLevel.MEDIUM,
                        float("semanticScore"), zone("semanticZone"), 0, DangerCandidateProvenance.FIXTURE,
                        "semantic", metricDistanceMeters = float("semanticDistance"))
                    val fallback = DangerCandidate("fallback", source("fallbackSource"), DangerLevel.MEDIUM,
                        float("fallbackScore"), zone("fallbackZone"), 0, DangerCandidateProvenance.FIXTURE,
                        "fallback", metricDistanceMeters = float("depth"))
                    DangerResolutionPolicy.shouldPreferSemantic(semantic, fallback,
                        DangerResolutionContext(obstruction, true, bool("repeated")))
                }
                "preferLimited" -> {
                    val limited = DangerCandidate("limited", DangerSource.LIMITED_SENSORS, DangerLevel.MEDIUM,
                        float("limitedScore"), RgbZone.CENTER, 0, DangerCandidateProvenance.FIXTURE, "limited")
                    val fallback = DangerCandidate("fallback", source("fallbackSource"), DangerLevel.MEDIUM,
                        float("fallbackScore"), RgbZone.CENTER, 0, DangerCandidateProvenance.FIXTURE,
                        "fallback", metricDistanceMeters = float("depth"))
                    DangerResolutionPolicy.shouldPreferLimitedSensors(limited, fallback, null,
                        DangerResolutionContext(obstruction, false, false),
                        DangerLevel.valueOf(f.getValue("baseLevel").uppercase()))
                }
                else -> error("Unknown fixture type")
            }
            assertEquals(f.getValue("id"), f.getValue("expected").toBoolean(), actual)
            results += "${f.getValue("id")}=$actual"
        }
        println("ORIA_DANGER_FIXTURES " + results.joinToString(","))
    }
}
