package com.fileapex.platform

import android.content.Context
import com.fileapex.data.settings.androidAppContextOrNull

/** Backed by plain preferences (no secrets) so it can be read before the settings store is open. */
internal object AndroidWorkState {
    private const val PREFS = "fileapex_work_state"
    private const val WORK_DATABASE = "androidx.work.workdb"

    fun memory(context: Context): WorkScheduleMemory {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = object : WorkStateStore {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        }
        return WorkScheduleMemory(store) { context.getDatabasePath(WORK_DATABASE).exists() }
    }

    fun memoryOrNull(): WorkScheduleMemory? = androidAppContextOrNull()?.let(::memory)
}
