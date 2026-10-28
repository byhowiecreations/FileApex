package com.fileapex.platform

interface WorkStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Remembers whether a feature ever scheduled background work, so that turning it off does not start WorkManager just to
 * cancel jobs that were never there. An install that already has WorkManager state from before this check is asked
 * once; after that cancel is skipped until the feature schedules again.
 */
class WorkScheduleMemory(private val store: WorkStateStore, private val workDatabaseExists: () -> Boolean) {
    fun markScheduled(feature: String) = store.put(feature, SCHEDULED)

    fun markIdle(feature: String) = store.put(feature, IDLE)

    fun cancelNeeded(feature: String): Boolean = when (store.get(feature)) {
        SCHEDULED -> true
        IDLE -> false
        else -> workDatabaseExists()
    }

    private companion object {
        const val SCHEDULED = "scheduled"
        const val IDLE = "idle"
    }
}
