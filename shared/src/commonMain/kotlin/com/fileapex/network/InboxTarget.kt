package com.fileapex.network

import com.fileapex.util.PathUtils

/**
 * "Put it in your own receive folder." A sender that has no folder in mind names the destination `@inbox/<name>` and
 * the receiver decides where that is, so no device has to know another device's folders or platform. A folder the
 * user browsed to on the receiver is still an absolute path from its own listing and passes through unchanged.
 */
object InboxTarget {
    const val ROOT = "@inbox"

    fun isInbox(raw: String): Boolean = raw == ROOT || raw.startsWith("$ROOT/") || raw.startsWith("$ROOT\\")

    /** The absolute path under [inboxDir], the input itself for an ordinary path, or null when it climbs out. */
    fun resolve(raw: String, inboxDir: String): String? {
        if (!isInbox(raw)) return raw
        val parts = raw.removePrefix(ROOT).split('/', '\\').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) return null
        if (parts.isEmpty()) return inboxDir
        return PathUtils.join(inboxDir, parts.joinToString("/"))
    }
}
