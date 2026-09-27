package com.htc.vive.eagle.hackathon.starter.oria.recording

import org.json.JSONArray
import org.json.JSONObject

/** JSON adapter only. The privacy decisions are pure Kotlin and unit tested independently. */
object OriaLabPrivacyJson {
    fun sanitizeEvent(source: JSONObject): JSONObject = JSONObject(OriaLabPrivacy.sanitizeEvent(Read().map(source, 0)))
    fun sanitizeMetadata(source: JSONObject): JSONObject = JSONObject(OriaLabPrivacy.sanitizeMetadata(Read().map(source, 0)))

    private class Read {
        private var nodes = 0
        private fun visit(depth: Int) {
            require(depth <= OriaLabPrivacy.MAX_DEPTH && ++nodes <= OriaLabPrivacy.MAX_NODES) { "privacy_structure_limit" }
        }
        fun map(source: JSONObject, depth: Int): Map<String, Any?> {
            visit(depth)
            return source.keys().asSequence().associateWith { value(source.get(it), depth + 1) }
        }
        private fun value(source: Any?, depth: Int): Any? {
            visit(depth)
            return when (source) {
                null, JSONObject.NULL -> null
                is JSONObject -> map(source, depth + 1)
                is JSONArray -> (0 until source.length()).map { value(source.get(it), depth + 1) }
                is String, is Number, is Boolean -> source
                else -> error("privacy_invalid_scalar")
            }
        }
    }
}
