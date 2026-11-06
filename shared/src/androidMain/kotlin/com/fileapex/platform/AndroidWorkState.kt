package com.fileapex.platform

import android.content.Context
import com.fileapex.data.seal.SealedStrings
import com.fileapex.data.settings.androidAppContextOrNull

/** Sealed preferences: which features ever scheduled background work. With no key the memory reads empty and the caller checks WorkManager itself. */
internal object AndroidWorkState {
    private const val PREFS = "fileapex_work_state"
    private const val WORK_DATABASE = "androidx.work.workdb"

    fun memory(context: Context): WorkScheduleMemory {
        val sealed = SealedStrings(PrefsStringSlots(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), "work-state")
        val store = object : WorkStateStore {
            override fun get(key: String): String? = sealed.get(key)
            override fun put(key: String, value: String) { sealed.put(key, value) }
        }
        return WorkScheduleMemory(store) { context.getDatabasePath(WORK_DATABASE).exists() }
    }

    fun memoryOrNull(): WorkScheduleMemory? = androidAppContextOrNull()?.let(::memory)
}
