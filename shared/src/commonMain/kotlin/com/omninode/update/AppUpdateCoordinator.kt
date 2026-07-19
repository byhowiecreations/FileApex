package com.omninode.update

import com.omninode.di.OmniNodeServices
import com.omninode.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Schedules background update checks when Auto-Update is enabled,
 * using the user-configured Hours/Days/Weeks interval.
 */
object AppUpdateCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var inFlight = false
    private var schedulerJob: Job? = null

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Call once after [OmniNodeServices.init] when the process starts. */
    fun onAppLaunch() {
        ensureSchedulerRunning()
        if (OmniNodeServices.settings.autoUpdateEnabled.value) {
            scheduleCheck(reason = "launch", force = false)
        }
    }

    /** Call when the user turns Auto-Update on in Settings. */
    fun onAutoUpdateEnabled() {
        ensureSchedulerRunning()
        scheduleCheck(reason = "settings", force = true)
    }

    /** Call when the user changes the check frequency. */
    fun onScheduleChanged() {
        restartScheduler()
    }

    fun onAutoUpdateDisabled() {
        _statusMessage.value = "Auto-Update off"
    }

    private fun restartScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
        ensureSchedulerRunning()
    }

    private fun ensureSchedulerRunning() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                val settings = OmniNodeServices.settings
                if (!settings.autoUpdateEnabled.value) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val intervalMs = settings.autoUpdateIntervalMillis().coerceAtLeast(MIN_INTERVAL_MS)
                val last = settings.lastUpdateCheckEpochMs.value
                val now = currentTimeMillis()
                val due = last <= 0L || now - last >= intervalMs
                if (due) {
                    scheduleCheck(reason = "interval", force = false)
                }
                val nextDueAt = (settings.lastUpdateCheckEpochMs.value.takeIf { it > 0L } ?: now) +
                    settings.autoUpdateIntervalMillis().coerceAtLeast(MIN_INTERVAL_MS)
                val sleepMs = (nextDueAt - currentTimeMillis())
                    .coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
                delay(sleepMs)
            }
        }
    }

    private fun scheduleCheck(reason: String, force: Boolean) {
        scope.launch {
            val settings = OmniNodeServices.settings
            if (!settings.autoUpdateEnabled.value) return@launch
            if (!force) {
                val intervalMs = settings.autoUpdateIntervalMillis().coerceAtLeast(MIN_INTERVAL_MS)
                val last = settings.lastUpdateCheckEpochMs.value
                val now = currentTimeMillis()
                if (last > 0L && now - last < intervalMs) {
                    return@launch
                }
            }
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
                _statusMessage.value = "Checking for updates…"
                println("AppUpdateCoordinator: starting update check ($reason)")
                val result = AppUpdater.checkForUpdatesAndInstall()
                settings.setLastUpdateCheckEpochMs(currentTimeMillis())
                _statusMessage.value = result
            } catch (error: Throwable) {
                settings.setLastUpdateCheckEpochMs(currentTimeMillis())
                val message = error.message ?: "Update check failed"
                _statusMessage.value = message
                println("AppUpdateCoordinator: update check failed — $message")
                error.printStackTrace()
            } finally {
                gate.withLock { inFlight = false }
            }
        }
    }

    private const val IDLE_POLL_MS = 30_000L
    private const val MIN_INTERVAL_MS = 60_000L
    private const val MIN_SLEEP_MS = 15_000L
    private const val MAX_SLEEP_MS = 60L * 60L * 1000L
}
