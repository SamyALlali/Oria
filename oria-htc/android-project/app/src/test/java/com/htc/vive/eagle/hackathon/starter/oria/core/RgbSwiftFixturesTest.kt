package com.htc.vive.eagle.hackathon.starter.oria.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RgbSwiftFixturesTest {
    /** This parser deliberately supports only our flat, number/boolean/string JSONL fixtures. */
    private fun fields(line: String): Map<String, String> =
        Regex("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|true|false|-?[0-9]+(?:\\.[0-9]+)?)")
            .findAll(line).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    @Test fun samePortableFixturesAsExtractedSwiftFunctions() {
        val fixtures = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "fixtures/policy_cases.jsonl") }.firstOrNull { it.isFile }
            ?: error("Cannot find oria-htc/fixtures/policy_cases.jsonl")
        val results = mutableListOf<String>()
        for (line in fixtures.readLines().filter { it.isNotBlank() }) {
            val f = fields(line)
            fun float(name: String) = f.getValue(name).toFloat()
            fun long(name: String) = f.getValue(name).toLong()
            val actual: Any = when (f.getValue("type")) {
                "zone" -> RgbZone.fromCenterX(float("x")).voiceSuffix
                "qualification" -> RgbAlertPolicy.qualifies(Detection(f.getValue("classId").toInt(),
                    float("confidence"), Box(float("left"), float("top"), float("right"), float("bottom"))))
                "fresh" -> RgbAlertPolicy.isFresh(long("observedAtMs"), long("nowMs"), long("lifetimeMs"))
                "expired" -> RgbAlertPolicy.hasExpired(long("observedAtMs"), long("nowMs"), long("lifetimeMs"))
                else -> error("Unknown fixture type")
            }
            val jsonValue = if (actual is String) "\"$actual\"" else actual.toString()
            results += "{\"id\":\"${f.getValue("id")}\",\"result\":$jsonValue}"
            assertEquals(f.getValue("id"), f.getValue("expected"), actual.toString())
        }
        File(fixtures.parentFile, "results_kotlin.jsonl").writeText(results.joinToString("\n", postfix = "\n"))
    }
}
