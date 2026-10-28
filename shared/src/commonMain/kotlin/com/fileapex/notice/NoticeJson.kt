package com.fileapex.notice

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object NoticeJson {
    private const val MAX_DEPTH = 8

    /** The JSON reader recurses per nesting level, so a hostile file of thousands of brackets is refused before it is parsed. */
    fun parseObject(raw: String): JsonObject? {
        if (!withinDepth(raw)) return null
        return try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun withinDepth(raw: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in raw) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}
