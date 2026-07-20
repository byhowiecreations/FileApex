package com.omninode.update

import com.omninode.di.OmniNodeServices
import com.omninode.platform.BriefToast
import com.omninode.util.TimeUtils
import com.omninode.util.TimestampDiagnostics
import com.omninode.platform.notifyAppUpdateAvailable
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
 * Schedules background update checks when Check for Updates is enabled,
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
        if (OmniNodeServices.settings.checkForUpdatesEnabled.value) {
            scheduleCheck(
                reason = "launch",
                force = false,
                requireEnabled = true,
                toastFeedback = false
            )
        }
    }

    /** Call when the user turns Check for Updates on in Settings. */
    fun onCheckForUpdatesEnabled() {
        ensureSchedulerRunning()
        scheduleCheck(
            reason = "settings",
            force = true,
            requireEnabled = true,
            toastFeedback = false
        )
    }

    /** Call when the user changes the check frequency. */
    fun onScheduleChanged() {
        restartScheduler()
    }

    fun onCheckForUpdatesDisabled() {
        _statusMessage.value = "Check for Updates off"
    }

    /** Immediate network update check that bypasses interval timers. */
    fun checkNowManual() {
        BriefToast.show("Checking…")
        scheduleCheck(
            reason = "manual",
            force = true,
            requireEnabled = false,
            toastFeedback = true
        )
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
                if (!settings.checkForUpdatesEnabled.value) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val intervalMs = settings.checkForUpdatesIntervalMillis().coerceAtLeast(MIN_INTERVAL_MS)
                val last = settings.lastUpdateCheckEpochMs.value
                val due = last <= 0L || TimeUtils.millisSince(last) >= intervalMs
                if (due) {
                    scheduleCheck(
                        reason = "interval",
                        force = false,
                        requireEnabled = true,
                        toastFeedback = false
                    )
                }
                val now = TimeUtils.now()
                val nextDueAt = (settings.lastUpdateCheckEpochMs.value.takeIf { it > 0L } ?: now) +
                    settings.checkForUpdatesIntervalMillis().coerceAtLeast(MIN_INTERVAL_MS)
                val sleepMs = (nextDueAt - TimeUtils.now())
                    .coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
                delay(sleepMs)
            }
        }
    }

    private fun scheduleCheck(
        reason: String,
        force: Boolean,
        requireEnabled: Boolean,
        toastFeedback: Boolean
    ) {
        scope.launch {
            val settings = OmniNodeServices.settings
            if (requireEnabled && !settings.checkForUpdatesEnabled.value) return@launch
            if (!force) {
                val intervalMs = settings.checkForUpdatesIntervalMillis().coerceAtLeast(MIN_INTERVAL_MS)
                val last = settings.lastUpdateCheckEpochMs.value
                if (last > 0L && TimeUtils.millisSince(last) < intervalMs) {
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
                if (!toastFeedback) {
                    _statusMessage.value = "Checking for updates…"
                }
                println("AppUpdateCoordinator: starting update check ($reason)")
                when (
                    val outcome = AppUpdater.checkForUpdatesAndInstall { installing ->
                        val detail = buildUpdateDetail(installing)
                        notifyAppUpdateAvailable(installing.remoteVersion, detail)
                        _statusMessage.value =
                            "OmniNode ${installing.remoteVersion} available — installing…"
                    }
                ) {
                    is UpdateCheckOutcome.AlreadyCurrent -> {
                        settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                        _statusMessage.value = "On Current Version"
                        if (toastFeedback) {
                            BriefToast.show("On Current Version")
                        }
                    }
                    is UpdateCheckOutcome.Installing -> {
                        settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                    }
                }
            } catch (error: Throwable) {
                settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                val message = error.message ?: "Update check failed"
                _statusMessage.value = message
                if (toastFeedback) {
                    BriefToast.show(message)
                }
                println("AppUpdateCoordinator: update check failed — $message")
                error.printStackTrace()
            } finally {
                gate.withLock { inFlight = false }
            }
        }
    }

    private fun buildUpdateDetail(outcome: UpdateCheckOutcome.Installing): String {
        val title = outcome.releaseTitle
        val notes = outcome.releaseNotes?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(6)
            ?.joinToString(separator = "\n")
        return buildString {
            if (!title.isNullOrBlank() && title != outcome.remoteVersion) {
                append(title)
            }
            if (!notes.isNullOrBlank()) {
                if (isNotEmpty()) append('\n')
                append(notes)
            }
            if (isEmpty()) {
                append("A newer build is ready. Installing…")
            }
        }
    }

    private const val IDLE_POLL_MS = 30_000L
    private const val MIN_INTERVAL_MS = 60_000L
    private const val MIN_SLEEP_MS = 15_000L
    private const val MAX_SLEEP_MS = 60L * 60L * 1000L
}
