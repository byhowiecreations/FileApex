package com.fileapex.presentation

/**
 * Resolves OEM model identifiers (e.g. CPH2609, PKB110, SM-S928B) to friendly marketing names
 * and detects manufacturers to prevent false-positive substring matches in icon resolvers.
 */
object DeviceModelLookup {

    private val modelToName = mapOf(
        // Oppo Find X series
        "find x9 pro" to "OPPO Find X9 Pro",
        "find x9" to "OPPO Find X9",
        "oppo find x9 pro" to "OPPO Find X9 Pro",
        "oppo find x9" to "OPPO Find X9",
        "pkb110" to "OPPO Find X8 Pro",
        "pkc110" to "OPPO Find X8",
        "pkc130" to "OPPO Find X8 Ultra",
        "cph2651" to "OPPO Find X8",
        "cph2659" to "OPPO Find X8 Pro",
        "cph2609" to "OPPO Find X7 Ultra",
        "phy110" to "OPPO Find X7",
        "phz110" to "OPPO Find X7 Ultra",
        "cph2499" to "OPPO Find N3",
        "cph2519" to "OPPO Find N3 Flip",
        "pgfm10" to "OPPO Find N2",
        "pgu110" to "OPPO Find N2 Flip",
        // Honor Magic & X series
        "ali-nx1" to "HONOR X9b",
        "crt-nx1" to "HONOR X9a",
        "any-nx1" to "HONOR X9",
        "bvl-an16" to "HONOR Magic6 Pro",
        "bvp-an10" to "HONOR Magic V3",
        "vls-an00" to "HONOR Magic V5",
        // Samsung Galaxy S & Z series
        "sm-s928" to "Galaxy S24 Ultra",
        "sm-s938" to "Galaxy S25 Ultra",
        "sm-s948" to "Galaxy S26 Ultra",
        "sm-f946" to "Galaxy Z Fold 5",
        "sm-f956" to "Galaxy Z Fold 6",
        "sm-f966" to "Galaxy Z Fold 7",
        "sm-f731" to "Galaxy Z Flip 5",
        "sm-f741" to "Galaxy Z Flip 6",
        // Google Pixel
        "g10" to "Pixel 10",
        "g10p" to "Pixel 10 Pro",
        "g11" to "Pixel 11",
        "g11p" to "Pixel 11 Pro",
        "g11pf" to "Pixel 11 Pro Fold"
    )

    fun lookupMarketingName(model: String): String? {
        val trimmed = model.trim().lowercase()
        if (trimmed.isBlank()) return null
        modelToName[trimmed]?.let { return it }
        for ((key, name) in modelToName) {
            if (trimmed.startsWith(key) || key.startsWith(trimmed)) {
                return name
            }
        }
        return null
    }

    fun inferMake(model: String): String? {
        val trimmed = model.trim().lowercase()
        if (trimmed.isBlank()) return null
        return when {
            trimmed.startsWith("cph") || trimmed.startsWith("pkb") || trimmed.startsWith("pkc") ||
                trimmed.startsWith("phy") || trimmed.startsWith("phz") || trimmed.startsWith("pgf") ||
                "oppo" in trimmed || "find x" in trimmed || "find-x" in trimmed -> "oppo"
            trimmed.startsWith("sm-") -> "samsung"
            trimmed.startsWith("ali-") || trimmed.startsWith("crt-") || trimmed.startsWith("any-") ||
                trimmed.startsWith("bvl-") || trimmed.startsWith("bvp-") || trimmed.startsWith("vls-") -> "honor"
            trimmed.startsWith("pixel") -> "google"
            trimmed.startsWith("231") || trimmed.startsWith("241") || trimmed.startsWith("250") -> "xiaomi"
            trimmed.startsWith("v23") || trimmed.startsWith("v24") -> "vivo"
            else -> null
        }
    }
}
