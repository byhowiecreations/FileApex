package com.omninode.update

import com.omninode.di.OmniNodeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Schedules background update checks when Auto-Update is enabled.
 */
object AppUpdateCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var inFlight = false

    /** Call once after [OmniNodeServices.init] when the process starts. */
    fun onAppLaunch() {
        if (OmniNodeServices.settings.autoUpdateEnabled.value) {
            scheduleCheck(reason = "launch")
        }
    }

    /** Call when the user turns Auto-Update on in Settings. */
    fun onAutoUpdateEnabled() {
        scheduleCheck(reason = "settings")
    }

    private fun scheduleCheck(reason: String) {
        scope.launch {
            val shouldRun = gate.withLock {
                if (inFlight) {
                    println("AppUpdateCoordinator: check already in flight (skip $reason)")
                    false
                } else {
                    inFlight = true
                    true
                }
            }
            if (!shouldRun) return@launch
            try {
                println("AppUpdateCoordinator: starting update check ($reason)")
                AppUpdater.checkForUpdatesAndInstall()
            } catch (error: Throwable) {
                println("AppUpdateCoordinator: update check failed — ${error.message}")
                error.printStackTrace()
            } finally {
                gate.withLock { inFlight = false }
            }
        }
    }
}
