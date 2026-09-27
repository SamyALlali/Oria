package com.htc.vive.eagle.hackathon.starter.oria.recording

import org.json.JSONArray
import org.json.JSONObject

/** Last persistence boundary for Oria Lab. Runtime UI may use destination text, captures may not. */
object OriaLabPrivacy {
    private val privateKeys = setOf(
        "destination", "query", "address", "label", "name", "origin", "point",
        "latitude", "longitude", "instruction", "currentInstruction", "text",
    )

    internal fun mustOmit(key: String): Boolean = key in privateKeys

    fun sanitize(source: JSONObject): JSONObject {
        val result = JSONObject()
        var omitted = false
        source.keys().forEach { key ->
            if (mustOmit(key)) {
                omitted = true
            } else {
                result.put(key, sanitizeValue(source.get(key)))
            }
        }
        if (omitted) result.put("privateLocationDataOmitted", true)
        return result
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> sanitize(value)
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(sanitizeValue(value.get(index)))
        }
        else -> value
    }
}
