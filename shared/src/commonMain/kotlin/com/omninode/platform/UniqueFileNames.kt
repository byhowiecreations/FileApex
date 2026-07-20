package com.omninode.platform

import com.omninode.util.PathUtils
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Resolves a non-colliding absolute path: `photo.jpg` → `photo (1).jpg` → `photo (2).jpg` …
 * Same rules common file managers use when a destination name already exists.
 */
object UniqueFileNames {
    fun resolve(preferredAbsolutePath: String): String {
        val preferred = Path(preferredAbsolutePath)
        if (!SystemFileSystem.exists(preferred)) return preferredAbsolutePath
        val parent = preferred.parent?.toString() ?: return preferredAbsolutePath
        val fileName = preferredAbsolutePath
            .substringAfterLast('/')
            .substringAfterLast('\\')
        if (fileName.isBlank()) return preferredAbsolutePath
        return resolveInDirectory(parent, fileName)
    }

    fun resolveInDirectory(directory: String, fileName: String): String {
        val preferred = PathUtils.join(directory, fileName)
        if (!SystemFileSystem.exists(Path(preferred))) return preferred
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = PathUtils.join(directory, "$base ($index)$ext")
            if (!SystemFileSystem.exists(Path(candidate))) return candidate
            index++
        }
    }
}
