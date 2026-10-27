package com.fileapex.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.fileapex.util.TimeUtils

/** Writes how the previous process ended to the diagnostic log, once per exit, so a vendor kill leaves a trace. */
object ProcessExitLogger {
    private const val PREFS = "fileapex_process_exit"
    private const val KEY_LAST_EXIT_MS = "last_logged_exit_ms"
    private const val HISTORY = 16
    const val KILL_WINDOW_MS = 7 * 24 * 60 * 60_000L

    fun record(context: Context) {
        val exits = history(context)
        if (exits.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastLogged = prefs.getLong(KEY_LAST_EXIT_MS, 0L)
        val now = TimeUtils.now()
        exits.filter { it.timestamp > lastLogged }.sortedBy { it.timestamp }.forEach { exit ->
            Diagnostics.log(
                ProcessExitSummary.line(
                    exitAtMs = exit.timestamp,
                    nowMs = now,
                    reasonCode = exit.reason,
                    status = exit.status,
                    importance = exit.importance,
                    rssKb = exit.rss,
                    description = exit.description
                )
            )
        }
        prefs.edit().putLong(KEY_LAST_EXIT_MS, exits.maxOf { it.timestamp }).apply()
        val kills = ProcessExitSummary.recentSystemKills(exits.map { it.timestamp to it.reason }, now, KILL_WINDOW_MS)
        if (kills > 0) Diagnostics.log("system or vendor kills in the last 7 days: $kills")
    }

    private fun history(context: Context): List<android.app.ApplicationExitInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, HISTORY)
    }
}
