package com.fileapex.util

/**
 * Path join / normalize / within-root checks.
 * Browse UI, server API path allowlists, and transfer destination joins must use this.
 */
object PathUtils {
    /**
     * Normalize separators and trailing slashes.
     * Blank input becomes `/`.
     */
    fun normalize(path: String): String {
        if (path.isBlank()) return "/"
        val trimmed = path.replace('\\', '/').trimEnd('/')
        return trimmed.ifBlank { "/" }
    }

    /**
     * Normalize [path]; when blank, normalize [fallbackRoot] instead
     * (explorer browse-root semantics).
     */
    fun normalizeOr(path: String, fallbackRoot: String): String {
        if (path.isBlank()) return normalize(fallbackRoot)
        return normalize(path)
    }

    fun join(directory: String, name: String): String {
        val trimmed = directory.trimEnd('/', '\\')
        return "$trimmed/$name"
    }

    /**
     * True when [absolutePath] equals [rootPath] or is a descendant under that root.
     * Browse and server transfer allowlists must share this predicate.
     */
    fun isWithinRoot(absolutePath: String, rootPath: String): Boolean {
        val root = normalize(rootPath)
        val current = normalize(absolutePath)
        if (root == "/") {
            return current.startsWith("/")
        }
        return current == root || current.startsWith("$root/")
    }

    /** True when any segment of [path] is `..`, whichever separator is used. */
    fun hasParentSegment(path: String): Boolean = path.replace('\\', '/').split('/').any { it == ".." }

    /**
     * For a path on this device's own disk: true when [absolutePath] is inside [rootPath] after `..` and symbolic links
     * are resolved. [isWithinRoot] compares text only, so `<root>/../elsewhere` passes it. Never use this for a path that
     * belongs to another device.
     */
    fun isConfinedOnDisk(absolutePath: String, rootPath: String): Boolean {
        if (absolutePath.isBlank() || rootPath.isBlank() || hasParentSegment(absolutePath)) return false
        return try {
            val root = java.io.File(rootPath).canonicalFile.toPath()
            java.io.File(absolutePath).canonicalFile.toPath().startsWith(root)
        } catch (_: java.io.IOException) {
            false
        }
    }

    /** The last segment of [name] when it is a usable file name, otherwise null (blank, `.` or `..`). */
    fun safeFileName(name: String): String? =
        name.replace('\\', '/').substringAfterLast('/').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }

    fun resolveWithinRoot(path: String, browseRoot: String): String {
        val root = normalize(browseRoot)
        val normalized = normalizeOr(path, root)
        return if (isWithinRoot(normalized, root)) normalized else root
    }

    fun parentWithinRoot(path: String, browseRoot: String): String? {
        val root = normalize(browseRoot)
        val current = normalize(path)
        if (current == root) return null
        val slash = current.lastIndexOf('/')
        if (slash < 0) return null
        val parent = if (slash == 0) "/" else current.substring(0, slash)
        val normalizedParent = normalize(parent)
        return if (isWithinRoot(normalizedParent, root)) normalizedParent else null
    }
}
