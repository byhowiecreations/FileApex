package com.fileapex.domain.diagnostics

/** The platform in a device snapshot can carry an edition ("Windows 11 Pro"); rules look at the family only. */
object DiagnosticsPlatform {
    fun family(platform: String): String {
        val name = platform.trim()
        return if (name.startsWith("Windows", ignoreCase = true)) "Windows" else name
    }
}
