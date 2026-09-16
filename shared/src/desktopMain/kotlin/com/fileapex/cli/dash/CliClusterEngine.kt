package com.fileapex.cli.dash

import com.fileapex.cli.CliDeviceAliasManager
import com.fileapex.cli.CliDeviceResolver
import com.fileapex.cli.ipc.CliActiveTransfer
import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliDeviceStatus
import com.fileapex.cli.ipc.CliIpcPacket
import com.fileapex.cli.ipc.CliQueueItem
import com.fileapex.cli.ipc.CliRemoteFile
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.platform.PlatformClipboard
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object CliClusterEngine {

    private var cachedClusterState: CliClusterState? = null
    private var lastClusterFetchTimeMs = 0L
    private val batteryCache = ConcurrentHashMap<String, Pair<Int, String>>()
    /** Peer `/clipboard/status` sharingEnabled, keyed by deviceId. */
    private val peerClipboardEnabled = ConcurrentHashMap<String, Boolean>()
    private val peerClipboardProbeEpochMs = ConcurrentHashMap<String, Long>()

    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mirrorStarted = AtomicBoolean(false)
    private var mirrorJob: Job? = null
    @Volatile
    private var mirroredDevices: List<com.fileapex.data.db.PairedDeviceEntity> = emptyList()
    @Volatile
    private var mirroredQueueCount: Int = 0

    /**
     * Keeps an in-memory cluster snapshot warm from the main app's presence/device flows
     * so `fileapex dash` can paint with zero cold-start delay when the GUI is running.
     * Must run only after [FileApexServices] database init.
     */
    fun ensureLiveMirror() {
        if (!FileApexServices.isDatabaseReady()) return
        if (!mirrorStarted.compareAndSet(false, true)) return
        mirrorJob = mirrorScope.launch {
            runCatching {
                val repo = FileApexServices.deviceRepository
                val presence = FileApexServices.presenceMonitor
                val settings = FileApexServices.settings
                launch {
                    repo.observeDevices().collectLatest { devices ->
                        mirroredDevices = devices.filter {
                            it.deviceId.isNotBlank() && it.publicKeyHash.isNotBlank()
                        }
                        rebuildCachedState(
                            devices = mirroredDevices,
                            highlightBattery = false,
                            forceBatteryRefresh = false
                        )
                    }
                }
                launch {
                    val presenceSignals = combine(
                        presence.onlineDeviceIds,
                        presence.onlineSnapshotEpochMs,
                        presence.reachabilityEpochMs
                    ) { online, snap, reach -> Triple(online, snap, reach) }
                    val clipboardSignals = combine(
                        settings.clipboardSharingEnabled,
                        settings.clipboardShareMode,
                        settings.clipboardTargetDeviceIds
                    ) { enabled, mode, targets -> Triple(enabled, mode, targets) }
                    combine(presenceSignals, clipboardSignals) { _, _ -> }
                        .collectLatest {
                            rebuildCachedState(
                                devices = mirroredDevices.ifEmpty {
                                    runCatching { CliDeviceResolver.getAuthenticatedDevices(repo) }.getOrDefault(emptyList())
                                },
                                highlightBattery = false,
                                forceBatteryRefresh = false
                            )
                        }
                }
                launch {
                    FileApexServices.transferQueue.pendingItems.collectLatest { items ->
                        mirroredQueueCount = items.size
                        val current = cachedClusterState
                        if (current != null && current.queueCount != items.size) {
                            cachedClusterState = current.copy(queueCount = items.size)
                        }
                    }
                }
                launch {
                    TransferActivityGuard.statsFlow.collectLatest {
                        rebuildCachedState(
                            devices = mirroredDevices,
                            highlightBattery = false,
                            forceBatteryRefresh = false
                        )
                    }
                }
                // Seed immediately from current roster
                mirroredDevices = runCatching {
                    CliDeviceResolver.getAuthenticatedDevices(repo)
                }.getOrDefault(emptyList())
                rebuildCachedState(mirroredDevices, highlightBattery = false, forceBatteryRefresh = true)
            }.onFailure {
                mirrorStarted.set(false)
            }
        }
    }

    suspend fun fetchClusterState(highlightBattery: Boolean = false, force: Boolean = false): CliClusterState = withContext(Dispatchers.IO) {
        ensureLiveMirror()
        // Prefer mirrored roster (already in memory from main app) over a fresh Room round-trip.
        val now = System.currentTimeMillis()
        if (!force && cachedClusterState != null && (now - lastClusterFetchTimeMs < 50L)) {
            return@withContext cachedClusterState!!
        }
        if (!force && !TransferActivityGuard.isTransferActive() &&
            cachedClusterState != null &&
            cachedClusterState!!.devices.isNotEmpty() &&
            (now - lastClusterFetchTimeMs < 300L)
        ) {
            return@withContext cachedClusterState!!
        }

        val devices = if (mirroredDevices.isNotEmpty()) {
            mirroredDevices
        } else {
            CliDeviceResolver.getAuthenticatedDevices(FileApexServices.deviceRepository)
        }

        rebuildCachedState(devices, highlightBattery, forceBatteryRefresh = force || highlightBattery)
        // First paint: never wait on empty mirror — return whatever we just built (may still be filling).
        cachedClusterState ?: CliClusterState(0, 0, 0, 0)
    }

    private fun rebuildCachedState(
        devices: List<com.fileapex.data.db.PairedDeviceEntity>,
        highlightBattery: Boolean,
        forceBatteryRefresh: Boolean
    ) {
        val activeStats = TransferActivityGuard.statsFlow.value
        val isGuardActive = TransferActivityGuard.isTransferActive()
        val now = System.currentTimeMillis()
        val isTransferring = isGuardActive || activeStats.isActive

        val settings = FileApexServices.settings
        val presence = FileApexServices.presenceMonitor
        val onlineIds = presence.onlineDeviceIds.value

        val sharingEnabled = settings.clipboardSharingEnabled.value
        val mode = settings.clipboardShareMode.value
        val targetIds = settings.clipboardTargetDeviceIds.value
        val localClipTargets = if (!sharingEnabled) {
            emptySet()
        } else {
            ClipboardSharePolicy.resolveTargetIds(
                mode = mode,
                pairedDeviceIds = devices.map { it.deviceId },
                selectedDeviceIds = targetIds
            )
        }

        val deviceStatuses = devices.mapIndexed { idx, dev ->
            val slug = CliDeviceAliasManager.resolveEffectiveSlug(dev)
            val name = dev.deviceName.ifBlank { "Unknown" }

            val isOnline = presence.isDeviceOnline(dev) || (dev.deviceId in onlineIds)
            val isCellular = false
            val direct = dev.lastKnownIp.takeIf { it.isNotBlank() }

            var levelPercent: Int? = null
            var chargingState = ""

            if (isOnline && direct != null && dev.port > 0) {
                val cachedBat = batteryCache[dev.deviceId]
                if (cachedBat != null) {
                    levelPercent = cachedBat.first
                    chargingState = cachedBat.second
                }

                if (forceBatteryRefresh || highlightBattery || cachedBat == null) {
                    mirrorScope.launch {
                        val bat = withTimeoutOrNull(600) {
                            runCatching {
                                FileApexServices.client.fetchFastBattery(direct, dev.port)
                            }.getOrNull()
                        }
                        if (bat?.levelPercent != null && bat.levelPercent > 0) {
                            batteryCache[dev.deviceId] = Pair(bat.levelPercent, bat.chargingState)
                        }
                    }
                }

                // Refresh peer clipboard capability in background (non-blocking).
                val lastProbe = peerClipboardProbeEpochMs[dev.deviceId] ?: 0L
                if (now - lastProbe > 8_000L) {
                    peerClipboardProbeEpochMs[dev.deviceId] = now
                    mirrorScope.launch {
                        val status = withTimeoutOrNull(700) {
                            runCatching {
                                FileApexServices.client.getClipboardStatus(direct, dev.port)
                            }.getOrNull()
                        }
                        if (status != null) {
                            peerClipboardEnabled[dev.deviceId] = status.sharingEnabled
                        }
                    }
                }
            }

            val statusStr = when {
                !isOnline -> "OFFLINE"
                isCellular -> "CELLULAR"
                else -> "ONLINE"
            }

            val clipStatusStr = resolveClipboardStatusLabel(
                deviceId = dev.deviceId,
                localTargets = localClipTargets,
                isOnline = isOnline
            )
            val batteryBlockStr = formatBatteryBlocks(levelPercent, chargingState, highlightBattery)

            CliDeviceStatus(
                id = idx + 1,
                deviceId = dev.deviceId,
                deviceName = name,
                slugOrAlias = slug,
                batteryBlocks = batteryBlockStr,
                batteryPercent = levelPercent,
                chargingState = chargingState,
                clipboardStatus = clipStatusStr,
                status = statusStr,
                host = direct.orEmpty(),
                port = dev.port
            )
        }

        val onlineCount = deviceStatuses.count { it.status == "ONLINE" || it.status == "CELLULAR" }

        val activeTargetList = TransferActivityGuard.getActiveTransfers()
        val mappedTransfers = if (activeTargetList.isNotEmpty()) {
            activeTargetList.map { item ->
                val bFormatted = if (item.totalBytes > 0L) {
                    "${formatByteSize(item.sentBytes)} / ${formatByteSize(item.totalBytes)}"
                } else ""
                CliActiveTransfer(
                    deviceId = item.destinationDeviceId,
                    deviceName = item.destinationDeviceName,
                    fileName = item.currentFileName,
                    progress = item.progress,
                    speed = item.speedFormatted,
                    eta = item.etaFormatted,
                    bytesFormatted = bFormatted
                )
            }
        } else if (isTransferring) {
            val bFormatted = if (activeStats.totalBytes > 0L) {
                "${formatByteSize(activeStats.sentBytes)} / ${formatByteSize(activeStats.totalBytes)}"
            } else ""
            listOf(
                CliActiveTransfer(
                    deviceId = activeStats.destinationDeviceId,
                    deviceName = activeStats.destinationDeviceName,
                    fileName = activeStats.currentFileName,
                    progress = activeStats.progress,
                    speed = activeStats.speedFormatted,
                    eta = activeStats.etaFormatted,
                    bytesFormatted = bFormatted
                )
            )
        } else {
            emptyList()
        }

        val activeCount = mappedTransfers.size
        val queueCount = mirroredQueueCount

        val transferSummary = when {
            activeStats.currentFileName.isNotBlank() && activeStats.destinationDeviceName.isNotBlank() ->
                "${activeStats.currentFileName} -> ${activeStats.destinationDeviceName}"
            activeStats.currentFileName.isNotBlank() ->
                activeStats.currentFileName
            isTransferring ->
                "In-flight transfer"
            else -> ""
        }

        val transferLabel = when {
            transferSummary.isNotBlank() -> "Sending: $transferSummary"
            isTransferring -> "Sending..."
            else -> ""
        }

        val bytesFormatted = if (activeStats.totalBytes > 0L) {
            "${formatByteSize(activeStats.sentBytes)} / ${formatByteSize(activeStats.totalBytes)}"
        } else {
            ""
        }

        val progress = when {
            activeStats.totalBytes > 0L -> activeStats.progress
            isTransferring -> activeStats.progress
            else -> null
        }

        val state = CliClusterState(
            onlinePeerCount = onlineCount,
            totalPeerCount = devices.size,
            activeTransfersCount = activeCount,
            queueCount = queueCount,
            activeTransferProgress = progress,
            activeTransferSpeed = activeStats.speedFormatted,
            activeTransferEta = activeStats.etaFormatted,
            activeTransferSummary = transferSummary,
            activeTransferBytesFormatted = bytesFormatted,
            activeTransferLabel = transferLabel,
            activeTransfers = mappedTransfers,
            devices = deviceStatuses
        )
        cachedClusterState = state
        lastClusterFetchTimeMs = now
    }

    /**
     * Prefer live peer clipboard status when known; otherwise fall back to local share targets
     * (same policy the GUI uses for who receives clipboard).
     */
    private fun resolveClipboardStatusLabel(
        deviceId: String,
        localTargets: Set<String>,
        isOnline: Boolean
    ): String {
        val peer = peerClipboardEnabled[deviceId]
        if (peer != null) {
            return if (peer) "Enabled" else "Disabled"
        }
        if (deviceId in localTargets) {
            return "Enabled"
        }
        // Offline peers with no probe: if sharing is ALL/SPECIFIC targeting them we already
        // returned Enabled above. Otherwise Disabled.
        if (!isOnline) return "Disabled"
        return "Disabled"
    }

    private fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "${(kb * 10).toInt() / 10.0} KB"
        val mb = kb / 1024.0
        if (mb < 1024.0) return "${(mb * 10).toInt() / 10.0} MB"
        val gb = mb / 1024.0
        return "${(gb * 100).toInt() / 100.0} GB"
    }

    fun formatBatteryBlocks(levelPercent: Int?, chargingState: String, highlight: Boolean = false): String {
        if (levelPercent == null || levelPercent < 0) return "[-----] --"
        val isAc = chargingState.equals("AC", ignoreCase = true) || chargingState.contains("AC", ignoreCase = true)
        if (isAc && levelPercent >= 99) {
            return "[AC 100%]"
        }
        val filled = (levelPercent / 20).coerceIn(0, 5)
        val empty = 5 - filled
        val blockChar = if (highlight) "■" else "■"
        val emptyChar = "□"
        val blocks = blockChar.repeat(filled) + emptyChar.repeat(empty)
        val acPrefix = if (isAc) "AC " else ""
        return "[$blocks] $acPrefix$levelPercent%"
    }

    suspend fun sendLocalPaths(paths: List<String>, deviceIds: List<String>): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val transferQueue = FileApexServices.transferQueue
        val validPaths = paths.map { File(it).absolutePath }.filter { File(it).exists() }
        if (validPaths.isEmpty()) {
            return@withContext CliIpcPacket.DashActionResult(false, "No valid files or folders selected.")
        }
        if (deviceIds.isEmpty()) {
            return@withContext CliIpcPacket.DashActionResult(false, "No target devices selected.")
        }

        return@withContext try {
            val result = transferQueue.sendLocalPathsOrQueue(validPaths, deviceIds)
            if (result.hadImmediateSend) {
                val msg = result.batch?.summaryMessage?.ifBlank { null }
                    ?: result.message.ifBlank { null }
                    ?: "Transfer completed successfully."
                CliIpcPacket.DashActionResult(true, msg)
            } else if (result.hadQueue) {
                CliIpcPacket.DashActionResult(true, "Device offline. Transfer staged to persistent queue.")
            } else {
                CliIpcPacket.DashActionResult(false, result.message.ifBlank { "Failed to send." })
            }
        } catch (e: Throwable) {
            transferQueue.enqueueLocalPaths(validPaths, deviceIds)
            CliIpcPacket.DashActionResult(true, "Transfer staged to queue: ${e.message}")
        }
    }

    suspend fun listRemoteFiles(deviceId: String, path: String): CliIpcPacket.DashRemoteList = withContext(Dispatchers.IO) {
        val repo = FileApexServices.deviceRepository
        val device = repo.getDevice(deviceId)
            ?: return@withContext CliIpcPacket.DashRemoteList(path, emptyList())

        val host = device.lastKnownIp.trim()
        val port = device.port
        if (host.isBlank() || port <= 0) {
            return@withContext CliIpcPacket.DashRemoteList(path, emptyList())
        }

        val items = runCatching {
            FileApexServices.client.listFiles(host, port, path)
        }.getOrDefault(emptyList())

        val remoteFiles = items.map { item ->
            CliRemoteFile(
                name = item.name,
                absolutePath = item.absolutePath,
                isDirectory = item.isDirectory,
                sizeBytes = item.sizeBytes
            )
        }
        CliIpcPacket.DashRemoteList(path, remoteFiles)
    }

    suspend fun retrieveRemoteItem(
        deviceId: String,
        remotePath: String,
        isDirectory: Boolean
    ): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val repo = FileApexServices.deviceRepository
        val device = repo.getDevice(deviceId)
            ?: return@withContext CliIpcPacket.DashActionResult(false, "Unknown target device.")

        val host = device.lastKnownIp.trim()
        val port = device.port
        if (host.isBlank() || port <= 0) {
            return@withContext CliIpcPacket.DashActionResult(false, "Target device has no reachable host/port.")
        }

        val name = remotePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        val item = RemoteFileItem(
            id = remotePath,
            name = name,
            absolutePath = remotePath,
            sizeBytes = 0L,
            lastModified = 0L,
            isDirectory = isDirectory,
            mimeType = ""
        )

        return@withContext runCatching {
            val downloaded = FileApexServices.transferManager.downloadRemoteToDownloads(host, port, listOf(item))
            if (downloaded.isNotEmpty()) {
                CliIpcPacket.DashActionResult(true, "Downloaded ${downloaded.size} item(s) to Downloads/FileApex")
            } else {
                CliIpcPacket.DashActionResult(false, "Download produced no local files.")
            }
        }.getOrElse { e ->
            CliIpcPacket.DashActionResult(false, "Retrieve failed: ${e.message}")
        }
    }

    suspend fun sendClipboard(deviceId: String): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) {
            settings.setClipboardSharingEnabled(true)
        }
        return@withContext runCatching {
            ClipboardShareCoordinator.sendToDevice(deviceId)
            CliIpcPacket.DashActionResult(true, "Clipboard content dispatched to device.")
        }.getOrElse { e ->
            CliIpcPacket.DashActionResult(false, "Clipboard send failed: ${e.message}")
        }
    }

    suspend fun pullClipboard(deviceId: String): CliIpcPacket.DashClipPullResult = withContext(Dispatchers.IO) {
        val repo = FileApexServices.deviceRepository
        val device = repo.getDevice(deviceId)
            ?: return@withContext CliIpcPacket.DashClipPullResult(null, "Target device not found.")

        val host = device.lastKnownIp.trim()
        val port = device.port
        if (host.isBlank() || port <= 0) {
            return@withContext CliIpcPacket.DashClipPullResult(null, "Device is offline or missing IP.")
        }

        return@withContext runCatching {
            val text = FileApexServices.client.pullRemoteClipboard(host, port)
            if (text != null) {
                PlatformClipboard.setSystemClipboardText(text)
                CliIpcPacket.DashClipPullResult(text)
            } else {
                CliIpcPacket.DashClipPullResult(null, "Remote clipboard was empty.")
            }
        }.getOrElse { e ->
            CliIpcPacket.DashClipPullResult(null, "Pull failed: ${e.message}")
        }
    }

    suspend fun getQueue(): List<CliQueueItem> = withContext(Dispatchers.IO) {
        val transferQueue = FileApexServices.transferQueue
        val items = withTimeoutOrNull(500) { transferQueue.pendingItems.first() } ?: emptyList()
        items.mapIndexed { idx, item ->
            val targets = item.pendingDeviceNames.joinToString(", ").ifBlank { "Unknown" }
            CliQueueItem(
                index = idx + 1,
                id = item.id,
                sourceSummary = item.sourceSummary,
                targetDevices = targets,
                lastError = item.lastError
            )
        }
    }

    suspend fun removeFromQueue(indices: List<Int>): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val transferQueue = FileApexServices.transferQueue
        val items = withTimeoutOrNull(500) { transferQueue.pendingItems.first() } ?: emptyList()
        var removedCount = 0
        for (i in indices) {
            if (i in 1..items.size) {
                val item = items[i - 1]
                transferQueue.remove(item.id)
                removedCount++
            }
        }
        CliIpcPacket.DashActionResult(true, "Removed $removedCount item(s) from queue.")
    }
}
