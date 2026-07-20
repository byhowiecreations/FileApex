package com.omninode.util

/**
 * Single source of truth for path join / normalize / within-root security checks.
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
        return current == root || current.startsWith("$root/")
    }

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
        if (slash <= 0) return null
        val parent = current.substring(0, slash).ifBlank { "/" }
        val normalizedParent = normalize(parent)
        return if (isWithinRoot(normalizedParent, root)) normalizedParent else null
    }
}
