package com.fileapex.platform

import com.fileapex.domain.diagnostics.GpuDiagnostics
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Graphics cards from `system_profiler SPDisplaysDataType -json`. */
internal object MacGraphics {
    fun parse(output: String): List<GpuDiagnostics> {
        val root = try {
            Json.parseToJsonElement(output).jsonObject
        } catch (error: SerializationException) {
            println("MacGraphics: system_profiler output was not JSON")
            return emptyList()
        } catch (error: IllegalArgumentException) {
            println("MacGraphics: system_profiler output had no object")
            return emptyList()
        }
        val cards = root["SPDisplaysDataType"] as? JsonArray ?: return emptyList()
        return cards.mapNotNull { element ->
            val card = element as? JsonObject ?: return@mapNotNull null
            val model = card.text("sppci_model") ?: card.text("_name") ?: return@mapNotNull null
            val cores = card.text("sppci_cores")?.toIntOrNull()?.takeIf { it > 0 }
            val name = if (cores != null && model.startsWith("Apple")) "$model ($cores-core GPU)" else model
            GpuDiagnostics(name, card.text("spdisplays_vram")?.let(::sizeInBytes))
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun sizeInBytes(text: String): Long? {
        val match = Regex("""^([\d.]+)\s*(GB|MB)$""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = if (match.groupValues[2].equals("GB", ignoreCase = true)) 1024L * 1024 * 1024 else 1024L * 1024
        return (amount * unit).toLong().takeIf { it > 0 }
    }
}
