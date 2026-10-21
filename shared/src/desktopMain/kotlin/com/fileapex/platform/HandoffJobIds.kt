package com.fileapex.platform

import java.io.File

/** Job ids arrive in `fileapex://` URIs and job files, so they are never allowed to carry path segments. */
internal object HandoffJobIds {
    private val SAFE = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isSafe(jobId: String): Boolean = SAFE.matches(jobId)

    fun safeOrNull(jobId: String?): String? = jobId?.takeIf { isSafe(it) }

    /** Deletes `<root>/<jobId>` only when it resolves to a direct child of [root]. */
    fun deleteStaging(root: File, jobId: String) {
        if (!isSafe(jobId)) return
        val base = root.canonicalFile
        val dir = File(base, jobId).canonicalFile
        if (dir.parentFile != base || !dir.isDirectory) return
        dir.deleteRecursively()
    }
}
