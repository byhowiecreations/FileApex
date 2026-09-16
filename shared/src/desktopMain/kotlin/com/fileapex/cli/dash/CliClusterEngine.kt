package com.fileapex.cli.dash

import com.fileapex.cli.CliDeviceAliasManager
import com.fileapex.cli.CliDeviceResolver
import com.fileapex.cli.ipc.CliActiveTransfer
import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliDeviceStatus
import com.fileapex.cli.ipc.CliIpcPacket
import com.fileapex.cli.ipc.CliQueueItem
import com.fileapex.cli.ipc.CliRemoteFile
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.platform.PlatformClipboard
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object CliClusterEngine {

    private var cachedClusterState: CliClusterState? = null
    private var lastClusterFetchTimeMs = 0L
    private val batteryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()

    suspend fun fetchClusterState(highlightBattery: Boolean = false, force: Boolean = false): CliClusterState = withContext(Dispatchers.IO) {
        val activeStats = TransferActivityGuard.statsFlow.value
        val isGuardActive = TransferActivityGuard.isTransferActive()
        val now = System.currentTimeMillis()
        val isTransferring = isGuardActive || activeStats.isActive

        if (!force && !isTransferring && cachedClusterState != null && (now - lastClusterFetchTimeMs < 300L)) {
            return@withContext cachedClusterState!!
        }

        val repo = FileApexServices.deviceRepository
        val devices = CliDeviceResolver.getAuthenticatedDevices(repo)
        val settings = FileApexServices.settings
        val presence = FileApexServices.presenceMonitor
        val onlineIds = presence.onlineDeviceIds.value

        val sharingEnabled = settings.clipboardSharingEnabled.value
        val mode = settings.clipboardShareMode.value
        val targetIds = settings.clipboardTargetDeviceIds.value

        val deviceStatuses = devices.mapIndexed { idx, dev ->
            val slug = CliDeviceAliasManager.resolveEffectiveSlug(dev)
            val name = dev.deviceName.ifBlank { "Unknown" }

            // Primary authority is presenceMonitor in-memory state
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

                // If battery is uncached or highlight requested, refresh in background without blocking this call
                if (highlightBattery || cachedBat == null) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
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
            }

            val statusStr = when {
                !isOnline -> "OFFLINE"
                isCellular -> "CELLULAR"
                else -> "ONLINE"
            }

            val clipEnabled = sharingEnabled && (mode == ClipboardShareMode.ALL || dev.deviceId in targetIds)
            val clipStatusStr = if (clipEnabled) "Enabled" else "Disabled"
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
        val queueItems = runCatching { FileApexServices.transferQueue.pendingItems.first() }.getOrDefault(emptyList())
        val activeSendingItem = queueItems.firstOrNull { it.isSending }

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
        } else if (isTransferring || activeSendingItem != null) {
            val bFormatted = if (activeStats.totalBytes > 0L) {
                "${formatByteSize(activeStats.sentBytes)} / ${formatByteSize(activeStats.totalBytes)}"
            } else ""
            listOf(
                CliActiveTransfer(
                    deviceId = activeStats.destinationDeviceId,
                    deviceName = activeStats.destinationDeviceName,
                    fileName = activeStats.currentFileName.ifBlank { activeSendingItem?.displayLabel.orEmpty() },
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

        val transferSummary = when {
            activeStats.currentFileName.isNotBlank() && activeStats.destinationDeviceName.isNotBlank() ->
                "${activeStats.currentFileName} -> ${activeStats.destinationDeviceName}"
            activeStats.currentFileName.isNotBlank() ->
                activeStats.currentFileName
            activeSendingItem != null ->
                activeSendingItem.displayLabel
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
            activeSendingItem != null -> 0.0f
            isTransferring -> activeStats.progress
            else -> null
        }

        val state = CliClusterState(
            onlinePeerCount = onlineCount,
            totalPeerCount = devices.size,
            activeTransfersCount = activeCount,
            queueCount = queueItems.size,
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
        state
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
        val items = transferQueue.pendingItems.first()
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
        val items = transferQueue.pendingItems.first()
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
