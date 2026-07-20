package com.omninode.platform

import android.content.Context

/**
 * Defers share-server FGS start until [MainActivity] is in the foreground when background
 * promotion is blocked (Android 15+ dataSync / background FGS limits).
 */
object ShareServerPendingStart {
    private const val PREFS = "omninode_share_server"
    private const val KEY_PENDING = "pending_foreground_start"

    fun mark(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING, true)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING)
            .apply()
    }

    fun consume(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_PENDING, false)
        if (pending) {
            prefs.edit().remove(KEY_PENDING).apply()
        }
        return pending
    }
}
