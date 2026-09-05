package com.fileapex.update

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.defaultDownloadsDir
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
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Schedules background update checks when Check for Updates is enabled,
 * using the user-configured Hours/Days/Weeks interval.
 */
object AppUpdateCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var inFlight = false
    private var downloadInFlight = false
    private var schedulerJob: Job? = null

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _pendingUpdate = MutableStateFlow<PendingUpdateOffer?>(null)
    val pendingUpdate: StateFlow<PendingUpdateOffer?> = _pendingUpdate.asStateFlow()

    private val _showUpdateSheet = MutableStateFlow(false)
    val showUpdateSheet: StateFlow<Boolean> = _showUpdateSheet.asStateFlow()

    /** Re-post the update notification after a locale change, never for an older stored tag. */
    fun republishPendingNotificationIfNeeded() {
        restorePendingOffer()
        dropStalePendingOffer()
        val offer = _pendingUpdate.value ?: return
        notifyAppUpdateAvailable(offer)
    }

    /** Call once after [FileApexServices.init] when the process starts. */
    fun onAppLaunch() {
        syncInstallStatusOnAppOpen()
        restorePendingOffer()
        dropStalePendingOffer()
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

    fun syncInstallStatusOnAppOpen() {
        val lastNoteId = PendingUpdateStore.getLastAttemptedNoteId()
        val offer = _pendingUpdate.value ?: PendingUpdateStore.load()
        val targetNoteId = lastNoteId.ifBlank { offer?.originNoteId.orEmpty() }

        if (targetNoteId.isNotBlank()) {
            val currentStatus = PendingUpdateStore.getNoteInstallStatus(targetNoteId)
            val targetVersion = offer?.remoteVersion
            val isCurrentOrNewer = if (!targetVersion.isNullOrBlank()) {
                !isRemoteVersionNewer(currentAppVersionName(), targetVersion)
            } else {
                false
            }

            if (currentStatus == "INSTALLED" || isCurrentOrNewer) {
                PendingUpdateStore.setNoteInstallStatus(targetNoteId, "INSTALLED")
                PendingUpdateStore.setLastAttemptedNoteId("")
                setPendingOffer(null)
                dismissAppUpdateNotification()
                println("AppUpdateCoordinator: noteId=$targetNoteId status on app open: INSTALLED")
            } else {
                PendingUpdateStore.setNoteInstallStatus(targetNoteId, "NOT_INSTALLED")
                PendingUpdateStore.setLastAttemptedNoteId("")
                setPendingOffer(null)
                dismissAppUpdateNotification()
                println("AppUpdateCoordinator: noteId=$targetNoteId status on app open: NOT_INSTALLED (will not re-prompt)")
            }
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
        _statusMessage.value = com.fileapex.i18n.AppI18n.t("check_updates_off")
    }

    /** Immediate network update check that bypasses interval timers. */
    fun checkNowManual() {
        BriefToast.show(com.fileapex.i18n.AppI18n.t("checking"))
        scheduleCheck(
            reason = "manual",
            force = true,
            requireEnabled = false,
            toastFeedback = true
        )
    }

    fun requestShowUpdateSheet() {
        restorePendingOffer()
        dropStalePendingOffer()
        if (_pendingUpdate.value != null) {
            _showUpdateSheet.value = true
            return
        }
        // Process may have been killed after the notification was posted; re-probe.
        scheduleCheck(
            reason = "notification_open",
            force = true,
            requireEnabled = false,
            toastFeedback = true
        )
    }

    fun dismissUpdateSheet() {
        _showUpdateSheet.value = false
    }

    fun skipPendingUpdate() {
        restorePendingOffer()
        val offer = _pendingUpdate.value ?: return
        FileApexServices.settings.setSkippedUpdateVersion(offer.remoteVersion)
        setPendingOffer(null)
        _showUpdateSheet.value = false
        _statusMessage.value = AppI18n.t("skipped_update", offer.remoteVersion)
        dismissAppUpdateNotification()
    }

    fun downloadPendingUpdate() {
        restorePendingOffer()
        dropStalePendingOffer()
        var offer = _pendingUpdate.value
        if (offer == null) {
            offer = resolveFallbackBulletinApkOffer()
            if (offer != null) {
                setPendingOffer(offer)
            }
        }
        if (offer == null) {
            val candidateNote = FileApexServices.noteRepository.notes.value.reversed().firstOrNull { note ->
                com.fileapex.update.BulletinApkUpdatePolicy.matchesAutoUpdateApk(note.attachmentFileName) &&
                    PendingUpdateStore.getNoteInstallStatus(note.noteId) == null
            }
            if (candidateNote != null && FileApexServices.noteRepository.attachmentNeedsDownload(candidateNote)) {
                BriefToast.show(AppI18n.t("update_download_progress"))
                scope.launch {
                    FileApexServices.noteRepository.fetchAttachmentIfNeeded(candidateNote.noteId)
                }
                return
            }
            BriefToast.show(com.fileapex.i18n.AppI18n.t("update_details_missing"))
            scheduleCheck(
                reason = "notification_install",
                force = true,
                requireEnabled = false,
                toastFeedback = true
            )
            return
        }
        val localPath = offer.localFilePath?.takeIf { it.isNotBlank() }
        if (localPath != null && fileExists(localPath)) {
            _statusMessage.value = AppI18n.t("installing_update", offer.remoteVersion)
            dismissAppUpdateNotification()
            offer.originNoteId?.let { noteId ->
                PendingUpdateStore.setLastAttemptedNoteId(noteId)
                if (PendingUpdateStore.getNoteInstallStatus(noteId) == null) {
                    PendingUpdateStore.setNoteInstallStatus(noteId, "NOT_INSTALLED")
                }
            }
            PlatformUpdateInstaller.installAndRelaunch(localPath, offer.remoteVersion)
            setPendingOffer(null)
            _showUpdateSheet.value = false
            return
        }
        if (downloadInFlight) {
            BriefToast.show(com.fileapex.i18n.AppI18n.t("update_download_progress"))
            return
        }
        scope.launch {
            downloadInFlight = true
            try {
                _statusMessage.value = AppI18n.t("downloading_update", offer.remoteVersion)
                dismissAppUpdateNotification()
                offer.originNoteId?.let { noteId ->
                    PendingUpdateStore.setLastAttemptedNoteId(noteId)
                    if (PendingUpdateStore.getNoteInstallStatus(noteId) == null) {
                        PendingUpdateStore.setNoteInstallStatus(noteId, "NOT_INSTALLED")
                    }
                }
                AppUpdater.downloadAndInstall(offer)
                FileApexServices.settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                setPendingOffer(null)
                _showUpdateSheet.value = false
                _statusMessage.value = AppI18n.t("installing_update", offer.remoteVersion)
            } catch (error: Throwable) {
                val message = error.message ?: AppI18n.t("update_download_failed")
                _statusMessage.value = message
                BriefToast.show(message)
                println("AppUpdateCoordinator: download failed - $message")
                error.printStackTrace()
            } finally {
                downloadInFlight = false
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
                    _statusMessage.value = com.fileapex.i18n.AppI18n.t("checking_for_updates")
                }
                println("AppUpdateCoordinator: starting update check ($reason)")
                when (val outcome = AppUpdater.probeForUpdates()) {
                    is UpdateCheckOutcome.AlreadyCurrent -> {
                        settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                        dropStalePendingOffer()
                        _statusMessage.value = com.fileapex.i18n.AppI18n.t("on_current_version")
                        if (toastFeedback) {
                            BriefToast.show(com.fileapex.i18n.AppI18n.t("on_current_version"))
                        }
                    }
                    is UpdateCheckOutcome.Available -> {
                        settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                        if (isOfferSkipped(outcome.offer)) {
                            _statusMessage.value = com.fileapex.i18n.AppI18n.t("on_current_version")
                            if (toastFeedback) {
                                BriefToast.show(com.fileapex.i18n.AppI18n.t("on_current_version"))
                            }
                            return@launch
                        }
                        handleAvailableUpdate(outcome.offer, toastFeedback)
                    }
                    is UpdateCheckOutcome.Installing -> Unit
                }
            } catch (error: Throwable) {
                settings.setLastUpdateCheckEpochMs(TimeUtils.now())
                val message = error.message ?: AppI18n.t("update_check_failed")
                _statusMessage.value = message
                if (toastFeedback) {
                    BriefToast.show(message)
                }
                println("AppUpdateCoordinator: update check failed - $message")
                error.printStackTrace()
            } finally {
                gate.withLock { inFlight = false }
            }
        }
    }

    private suspend fun handleAvailableUpdate(offer: PendingUpdateOffer, toastFeedback: Boolean) {
        if (shouldDeferUpdateInstallToUser()) {
            setPendingOffer(offer)
            notifyAppUpdateAvailable(offer)
            _statusMessage.value = AppI18n.t("update_available_title", offer.remoteVersion)
            if (toastFeedback) {
                _showUpdateSheet.value = true
            }
            return
        }
        _statusMessage.value = AppI18n.t("update_available_installing", offer.remoteVersion)
        notifyAppUpdateAvailable(offer)
        AppUpdater.downloadAndInstall(offer)
        setPendingOffer(null)
    }

    fun setPendingOffer(offer: PendingUpdateOffer?) {
        _pendingUpdate.value = offer
        PendingUpdateStore.save(offer)
    }

    private fun restorePendingOffer() {
        if (_pendingUpdate.value != null) return
        val stored = PendingUpdateStore.load() ?: return
        _pendingUpdate.value = stored
    }

    private fun fileExists(pathString: String): Boolean {
        return runCatching {
            val p = Path(pathString)
            SystemFileSystem.exists(p)
        }.getOrDefault(false)
    }

    private fun dropStalePendingOffer() {
        val offer = _pendingUpdate.value ?: PendingUpdateStore.load() ?: return
        val localPath = offer.localFilePath?.takeIf { it.isNotBlank() }
        if (localPath != null && fileExists(localPath)) {
            if (_pendingUpdate.value == null) {
                _pendingUpdate.value = offer
            }
            return
        }
        if (offer.assetDownloadUrl.isBlank() && offer.assetName.isNotBlank()) {
            val fallback = resolveFallbackBulletinApkOffer()
            if (fallback != null) {
                _pendingUpdate.value = fallback
                PendingUpdateStore.save(fallback)
                return
            }
        }
        if (!isRemoteVersionNewer(currentAppVersionName(), offer.remoteVersion)) {
            setPendingOffer(null)
            dismissAppUpdateNotification()
            return
        }
        if (_pendingUpdate.value == null) {
            _pendingUpdate.value = offer
        }
    }

    private fun resolveFallbackBulletinApkOffer(): PendingUpdateOffer? {
        val noteRepo = FileApexServices.noteRepository
        val candidateNote = noteRepo.notes.value.reversed().firstOrNull { note ->
            BulletinApkUpdatePolicy.matchesAutoUpdateApk(note.attachmentFileName) &&
                PendingUpdateStore.getNoteInstallStatus(note.noteId) == null
        }
        if (candidateNote != null) {
            val fileName = candidateNote.attachmentFileName.orEmpty()
            val version = BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: "v0.0.0"
            if (isRemoteVersionNewer(currentAppVersionName(), version)) {
                val localPath = noteRepo.resolveLocalAttachmentPath(candidateNote)
                if (!localPath.isNullOrBlank() && fileExists(localPath)) {
                    val apkSize = runCatching { SystemFileSystem.metadataOrNull(Path(localPath))?.size }.getOrNull()
                        ?: candidateNote.attachmentSizeBytes
                    return PendingUpdateOffer(
                        remoteVersion = version,
                        releaseTitle = "FileApex $version",
                        releaseNotes = candidateNote.content.takeIf { it.isNotBlank() },
                        assetName = fileName,
                        assetDownloadUrl = "",
                        assetSizeBytes = apkSize,
                        localFilePath = localPath,
                        originNoteId = candidateNote.noteId
                    )
                }
            }
        }

        val downloadsDir = defaultDownloadsDir()
        val dir = Path(downloadsDir)
        if (SystemFileSystem.exists(dir)) {
            val apks = runCatching {
                SystemFileSystem.list(dir).filter { path ->
                    val name = path.name
                    BulletinApkUpdatePolicy.matchesAutoUpdateApk(name)
                }
            }.getOrNull().orEmpty()

            val newestApk = apks.maxByOrNull { path ->
                runCatching { SystemFileSystem.metadataOrNull(path)?.size }.getOrNull() ?: 0L
            }
            if (newestApk != null) {
                val meta = runCatching { SystemFileSystem.metadataOrNull(newestApk) }.getOrNull()
                if (meta != null && meta.size > 1024L) {
                    val fileName = newestApk.name
                    val version = BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: "v0.0.0"
                    if (isRemoteVersionNewer(currentAppVersionName(), version)) {
                        val matchingNote = noteRepo.notes.value.firstOrNull {
                            it.attachmentFileName == fileName
                        }
                        if (matchingNote == null || PendingUpdateStore.getNoteInstallStatus(matchingNote.noteId) == null) {
                            return PendingUpdateOffer(
                                remoteVersion = version,
                                releaseTitle = "FileApex $version",
                                releaseNotes = null,
                                assetName = fileName,
                                assetDownloadUrl = "",
                                assetSizeBytes = meta.size,
                                localFilePath = newestApk.toString(),
                                originNoteId = matchingNote?.noteId
                            )
                        }
                    }
                }
            }
        }
        return null
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
