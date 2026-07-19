package com.omninode.platform

/**
 * Shared destination roots for outbound transfers (main app Multi Copy + extension handoff).
 */
object TransferPaths {
    /** Matches Explorer Multi Copy when identity.downloadsPath is blank. */
    fun fallbackDownloadsPath(rootPath: String): String {
        val trimmed = rootPath.trimEnd('/', '\\')
        return "$trimmed/Download/OmniNode"
    }
}
