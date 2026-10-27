package com.fileapex.platform

/** Turns an Android `ApplicationExitInfo` record into one diagnostic line, so a kill is visible after the restart. */
object ProcessExitSummary {
    private val names = mapOf(
        1 to "EXIT_SELF",
        2 to "SIGNALED",
        3 to "LOW_MEMORY",
        4 to "CRASH",
        5 to "CRASH_NATIVE",
        6 to "ANR",
        7 to "INITIALIZATION_FAILURE",
        8 to "PERMISSION_CHANGE",
        9 to "EXCESSIVE_RESOURCE_USAGE",
        10 to "USER_REQUESTED",
        11 to "USER_STOPPED",
        12 to "DEPENDENCY_DIED",
        13 to "OTHER",
        14 to "FREEZER",
        15 to "PACKAGE_STATE_CHANGE",
        16 to "PACKAGE_UPDATED"
    )

    /** Killed by the system or a vendor memory manager, not by us, the user, a crash or an update. */
    private val systemKills = setOf(2, 3, 9, 13, 14)

    private const val MAX_DESCRIPTION = 80

    fun reasonName(code: Int): String = names[code] ?: "UNKNOWN($code)"

    fun isSystemKill(code: Int): Boolean = code in systemKills

    fun line(
        exitAtMs: Long,
        nowMs: Long,
        reasonCode: Int,
        status: Int,
        importance: Int,
        rssKb: Long,
        description: String?
    ): String {
        val kill = if (isSystemKill(reasonCode)) " (kill)" else ""
        val text = description?.trim().orEmpty().take(MAX_DESCRIPTION)
        return "previous process exit: ${reasonName(reasonCode)} status=$status importance=$importance " +
            "rss=${rssKb / 1024} MB ${age(nowMs - exitAtMs)} ago$kill" + if (text.isEmpty()) "" else " $text"
    }

    /** [exits] are (time, reason code) pairs; counts the system kills newer than [windowMs]. */
    fun recentSystemKills(exits: List<Pair<Long, Int>>, nowMs: Long, windowMs: Long): Int =
        exits.count { (at, code) -> isSystemKill(code) && nowMs - at in 0..windowMs }

    private fun age(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            seconds < 86_400 -> "${seconds / 3600} h"
            else -> "${seconds / 86_400} d"
        }
    }
}
