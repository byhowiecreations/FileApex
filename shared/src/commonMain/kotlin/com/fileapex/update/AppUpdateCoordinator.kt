package com.fileapex.update

import com.fileapex.di.FileApexServices
import com.fileapex.platform.BriefToast
import com.fileapex.platform.dismissAppUpdateNotification
import com.fileapex.platform.notifyAppUpdateAvailable
import com.fileapex.platform.shouldDeferUpdateInstallToUser
import com.fileapex.util.TimeUtils
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

    private val _pendingUpdate = MutableStateFlow<PendingUpdateOffer?>(null)
    val pendingUpdate: StateFlow<PendingUpdateOffer?> = _pendingUpdate.asStateFlow()

    private val _showUpdateSheet = MutableStateFlow(false)
    val showUpdateSheet: StateFlow<Boolean> = _showUpdateSheet.asStateFlow()

    /** Call once after [FileApexServices.init] when the process starts. */
    fun onAppLaunch() {
        ensureSchedulerRunning()
        if (FileApexServices.settings.checkForUpdatesEnabled.value) {
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

    fun requestShowUpdateSheet() {
        if (_pendingUpdate.value != null) {
            _showUpdateSheet.value = true
        }
    }

    fun dismissUpdateSheet() {
        _showUpdateSheet.value = false
    }

    fun skipPendingUpdate() {
        val offer = _pendingUpdate.value ?: return
        FileApexServices.settings.setSkippedUpdateVersion(offer.remoteVersion)
        _pendingUpdate.value = null
        _showUpdateSheet.value = false
        _statusMessage.value = "Skipped FileApex ${offer.remoteVersion}"
        dismissAppUpdateNotification()
    }

    fun downloadPendingUpdate() {
        val offer = _pendingUpdate.value ?: return
        scope.launch {
            runCatching {
                _statusMessage.value = "Downloading FileApex ${offer.remoteVersion}…"
                dismissAppUpdateNotification()
                AppUpdater.downloadAndInstall(offer)
                FileApexServices.settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                _pendingUpdate.value = null
                _showUpdateSheet.value = false
                _statusMessage.value = "Installing FileApex ${offer.remoteVersion}…"
            }.onFailure { error ->
                val message = error.message ?: "Update download failed"
                _statusMessage.value = message
                BriefToast.show(message)
                println("AppUpdateCoordinator: download failed — $message")
                error.printStackTrace()
            }
        }
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
                val settings = FileApexServices.settings
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
            val settings = FileApexServices.settings
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
                when (val outcome = AppUpdater.probeForUpdates()) {
                    is UpdateCheckOutcome.AlreadyCurrent -> {
                        settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                        _statusMessage.value = "On Current Version"
                        if (toastFeedback) {
                            BriefToast.show("On Current Version")
                        }
                    }
                    is UpdateCheckOutcome.Available -> {
                        settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                        if (isOfferSkipped(outcome.offer)) {
                            _statusMessage.value = "On Current Version"
                            if (toastFeedback) {
                                BriefToast.show("On Current Version")
                            }
                            return@launch
                        }
                        handleAvailableUpdate(outcome.offer, toastFeedback)
                    }
                    is UpdateCheckOutcome.Installing -> Unit
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

    private suspend fun handleAvailableUpdate(offer: PendingUpdateOffer, toastFeedback: Boolean) {
        if (shouldDeferUpdateInstallToUser()) {
            _pendingUpdate.value = offer
            notifyAppUpdateAvailable(offer)
            _statusMessage.value = "FileApex ${offer.remoteVersion} available"
            if (toastFeedback) {
                _showUpdateSheet.value = true
            }
            return
        }
        _statusMessage.value = "FileApex ${offer.remoteVersion} available — installing…"
        notifyAppUpdateAvailable(offer)
        AppUpdater.downloadAndInstall(offer)
    }

    private fun isOfferSkipped(offer: PendingUpdateOffer): Boolean {
        val skipped = FileApexServices.settings.skippedUpdateVersion.value.trim()
        if (skipped.isEmpty()) return false
        val skippedParsed = FileApexSemVer.parse(skipped) ?: return skipped == offer.remoteVersion
        val remoteParsed = FileApexSemVer.parse(offer.remoteVersion) ?: return false
        return remoteParsed <= skippedParsed
    }

    private const val IDLE_POLL_MS = 30_000L
    private const val MIN_INTERVAL_MS = 60_000L
    private const val MIN_SLEEP_MS = 15_000L
    private const val MAX_SLEEP_MS = 60L * 60L * 1000L
}
