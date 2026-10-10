package com.fileapex.domain.backup

import com.fileapex.di.FileApexServices
import com.fileapex.network.PeerUnreachableException
import com.fileapex.platform.DownloadsPaths
import com.fileapex.platform.fastScanDirectory
import com.fileapex.platform.generateDeviceId
import com.fileapex.util.PathUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BackupStatus {
    data object Idle : BackupStatus
    data class Running(val done: Int, val total: Int, val current: String) : BackupStatus
}

/** Outcome of the most recent run, for the status line. */
data class BackupSummary(val atMillis: Long, val uploaded: Int, val failed: Int, val error: String?)

data class BackupRunResult(
    val uploaded: Int = 0,
    val failed: Int = 0,
    /** The time budget ran out with files still waiting; run again soon. */
    val hasMore: Boolean = false,
    /** Destination missing or unreachable; nothing was attempted. */
    val unavailable: Boolean = false,
    /** Changed files left waiting because the destination app is too old to replace a file in place. */
    val needsDestinationUpdate: Int = 0,
)

/**
 * Sends new and changed files from the chosen folders to one paired device, keeping each folder's own
 * name at the destination. Never deletes anything; a changed file replaces its earlier backup copy. Progress is saved as it goes, so an interrupted run
 * resumes where it stopped and an unchanged file is never sent twice.
 */
object BackupEngine {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    private val _lastSummary = MutableStateFlow<BackupSummary?>(null)

    /** Set the moment a run ends, so the page never shows an older run's numbers. */
    val lastSummary: StateFlow<BackupSummary?> = _lastSummary.asStateFlow()

    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val SAVE_EVERY = 25
    private val skippedNamePrefixes = listOf(".trashed", ".pending", ".fileapex")

    suspend fun run(timeBudgetMs: Long = Long.MAX_VALUE): BackupRunResult = mutex.withLock {
        try {
            runLocked(timeBudgetMs)
        } finally {
            _status.value = BackupStatus.Idle
        }
    }

    private suspend fun runLocked(timeBudgetMs: Long): BackupRunResult {
        val config = FileApexServices.settings.backupConfig.value
        if (!config.isRunnable) return BackupRunResult()
        val device = FileApexServices.deviceRepository.getDevice(config.destinationDeviceId)
            ?: return BackupRunResult(unavailable = true)
        val direct = runCatching { FileApexServices.presenceMonitor.resolveOutboundEndpoint(device) }.getOrNull()
        val host = direct?.host ?: device.lastKnownIp
        val port = direct?.port ?: device.port
        val client = FileApexServices.client
        // Doubles as the reachability check: an unreachable destination ends the run before any work starts.
        val capabilities = runCatching { client.transferCapabilities(host, port) }.getOrNull()
            ?: return BackupRunResult(unavailable = true)

        val effectiveRoot = if (device.platform.trim().equals("android", ignoreCase = true) &&
            (device.rootPath.isBlank() || device.rootPath == "/")
        ) "/storage/emulated/0" else device.rootPath
        val destinationRoot = DownloadsPaths.resolveReceiveRoot("", effectiveRoot, device.platform)

        val manifest = BackupManifest.load(config.destinationDeviceId)
        val candidates = config.sources.flatMap { collect(it) }
        val plan = BackupPlan.build(candidates, manifest)
        // An older destination would save a changed file as "name (1)" instead of replacing it,
        // so until it is updated it only receives new files.
        val queue = if (capabilities.backupSync) plan.newFiles + plan.changedFiles else plan.newFiles
        val waitingForUpdate = if (capabilities.backupSync) 0 else plan.changedFiles.size

        val startedAt = TimeUtils.now()
        var uploaded = 0
        var failed = 0
        var consecutiveFailures = 0
        var lastError: String? = null
        var hasMore = false
        for ((index, file) in queue.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (TimeUtils.now() - startedAt > timeBudgetMs) {
                hasMore = true
                break
            }
            _status.value = BackupStatus.Running(index, queue.size, file.relativePath.substringAfterLast('/'))
            try {
                client.uploadFromLocal(
                    host = host,
                    port = port,
                    localSourcePath = file.absolutePath,
                    remoteTargetPath = PathUtils.join(destinationRoot, file.relativePath),
                    transactionId = generateDeviceId(),
                    transactionTimestampEpochMs = TimeUtils.now(),
                    backup = capabilities.backupSync
                )
                manifest.record(file)
                uploaded++
                consecutiveFailures = 0
                if (uploaded % SAVE_EVERY == 0) manifest.save()
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                manifest.save()
                throw cancel
            } catch (offline: PeerUnreachableException) {
                manifest.save()
                return BackupRunResult(uploaded, failed, hasMore = true, unavailable = uploaded == 0)
            } catch (error: Throwable) {
                lastError = "${file.relativePath}: ${error.message ?: error::class.simpleName}"
                failed++
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    hasMore = true
                    break
                }
            }
        }
        val finishedAt = TimeUtils.now()
        manifest.recordRun(finishedAt, uploaded, failed, lastError)
        manifest.save()
        _lastSummary.value = BackupSummary(finishedAt, uploaded, failed, lastError)
        return BackupRunResult(uploaded, failed, hasMore, needsDestinationUpdate = waitingForUpdate)
    }

    /** Every non-empty file below [source], tagged with a path that starts at the folder's own name. */
    private fun collect(source: String): List<BackupCandidate> {
        val root = source.trimEnd('/')
        val out = ArrayList<BackupCandidate>()
        fun walk(dir: String, relative: String) {
            val (folders, files) = runCatching { fastScanDirectory(dir) }.getOrDefault(emptyList<com.fileapex.domain.model.RemoteFileItem>() to emptyList())
            for (file in files) {
                // The receiver rejects empty uploads, and half-written media files are not worth keeping.
                if (file.sizeBytes <= 0L || skippedNamePrefixes.any { file.name.startsWith(it) }) continue
                out += BackupCandidate(file.absolutePath, "$relative/${file.name}", file.sizeBytes, file.lastModified)
            }
            for (folder in folders) walk(folder.absolutePath, "$relative/${folder.name}")
        }
        walk(root, root.substringAfterLast('/'))
        return out
    }
}
