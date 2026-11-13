package com.fileapex.data.settings

internal fun decodeKineticOffsets(encoded: String): Map<String, Pair<Float, Float>> {
    if (encoded.isBlank()) return emptyMap()
    val map = mutableMapOf<String, Pair<Float, Float>>()
    encoded.split(";").forEach { token ->
        if (token.isBlank()) return@forEach
        val pipeParts = token.split("|")
        if (pipeParts.size == 3) {
            val id = pipeParts[0]
            val dx = pipeParts[1].toFloatOrNull()
            val dy = pipeParts[2].toFloatOrNull()
            if (id.isNotBlank() && dx != null && dy != null) {
                map[id] = Pair(dx, dy)
                if (id.startsWith("cmp_")) map["cmp:" + id.removePrefix("cmp_")] = Pair(dx, dy)
                if (id.startsWith("exp_")) map["exp:" + id.removePrefix("exp_")] = Pair(dx, dy)
            }
        } else {
            val colonParts = token.split(":")
            if (colonParts.size == 3) {
                val id = colonParts[0]
                val dx = colonParts[1].toFloatOrNull()
                val dy = colonParts[2].toFloatOrNull()
                if (id.isNotBlank() && dx != null && dy != null) {
                    map[id] = Pair(dx, dy)
                    if (id.startsWith("cmp_")) map["cmp:" + id.removePrefix("cmp_")] = Pair(dx, dy)
                    if (id.startsWith("exp_")) map["exp:" + id.removePrefix("exp_")] = Pair(dx, dy)
                }
            }
        }
    }
    return map
}

internal fun decodeStringMap(encoded: String): Map<String, String> {
    if (encoded.isBlank()) return emptyMap()
    val map = mutableMapOf<String, String>()
    encoded.split(";").forEach { token ->
        if (token.isBlank()) return@forEach
        val parts = token.split("|", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank()) {
            map[parts[0]] = parts[1]
        }
    }
    return map
}

internal fun encodeStringMap(map: Map<String, String>): String {
    return map.entries.joinToString(";") { (k, v) ->
        val cleanKey = k.replace(";", "_").replace("|", "_")
        val cleanVal = v.replace(";", "_")
        "$cleanKey|$cleanVal"
    }
}
