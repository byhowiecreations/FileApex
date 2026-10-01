package com.fileapex.platform

import com.fileapex.util.PathUtils
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

    /** [reserved] holds paths already handed out in the same batch but not yet on disk. */
    fun resolveInDirectory(directory: String, fileName: String, reserved: Set<String> = emptySet()): String {
        fun isTaken(candidate: String) = candidate in reserved || SystemFileSystem.exists(Path(candidate))
        val preferred = PathUtils.join(directory, fileName)
        if (!isTaken(preferred)) return preferred
        var index = 1
        while (true) {
            val candidate = PathUtils.join(directory, numbered(fileName, index))
            if (!isTaken(candidate)) return candidate
            index++
        }
    }

    fun numbered(fileName: String, index: Int): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        return "$base ($index)$ext"
    }

    fun matchesOriginalOrCollision(originalFileName: String, candidateName: String): Boolean {
        if (originalFileName.isBlank() || candidateName.isBlank()) return false
        if (candidateName == originalFileName) return true
        val dot = originalFileName.lastIndexOf('.')
        val base = if (dot > 0) originalFileName.substring(0, dot) else originalFileName
        val ext = if (dot > 0) originalFileName.substring(dot) else ""
        if (!candidateName.startsWith(base) || (ext.isNotEmpty() && !candidateName.endsWith(ext))) {
            return false
        }
        val middle = if (ext.isEmpty()) {
            candidateName.removePrefix(base)
        } else {
            candidateName.removePrefix(base).removeSuffix(ext)
        }
        return COLLISION_MIDDLE.matches(middle)
    }

    private val COLLISION_MIDDLE = Regex(""" \([0-9]+\)""")
}
