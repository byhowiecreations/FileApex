package com.fileapex.domain.transfer

import com.fileapex.data.db.PendingTransferDao
import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.PendingTransferStatus
import com.fileapex.data.db.QueuedSourceSnapshot
import com.fileapex.data.db.QueuedTransferSourceKind
import com.fileapex.data.device.DeviceRepository
import com.fileapex.domain.presence.PeerPresenceMonitor
import com.fileapex.domain.presence.PeerLanReachabilityVerdict
import com.fileapex.network.PeerReachabilityMessages
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.fileapex.platform.generateDeviceId

data class PendingTransferItem(
    val id: String,
    val createdAtEpochMs: Long,
    val displayLabel: String,
    val pendingDeviceIds: List<String>,
    val pendingDeviceNames: List<String>,
    val sourceSummary: String,
    val lastError: String?
)

/**
 * Outcome of [TransferQueueCoordinator.sendOrQueue] — immediate send plus optional queue.
 */
data class QueueAwareSendResult(
    val batch: TransferBatchResult?,
    val queuedDeviceNames: List<String>,
    val message: String
) {
    val hadImmediateSend: Boolean get() = batch != null && !batch.allFailed
    val hadQueue: Boolean get() = queuedDeviceNames.isNotEmpty()
}

/**
 * Persists deferred outbound transfers (source paths only) and drains them when peers
 * become LAN-reachable. All sends still go through [TransferManager].
 */
class TransferQueueCoordinator(
    private val dao: PendingTransferDao,
    private val deviceRepository: DeviceRepository,
    private val transferManager: TransferManager,
    private val presenceMonitor: PeerPresenceMonitor,
    private val scope: CoroutineScope
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val drainMutex = Mutex()
    private var drainWatcherStarted = false

    val pendingItems: Flow<List<PendingTransferItem>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toUiItem() } }

    val pendingCount: Flow<Int> =
        dao.observeCountByStatus(PendingTransferStatus.Queued.name)

    fun ensureDrainWatcher() {
        if (drainWatcherStarted) return
        drainWatcherStarted = true
        scope.launch {
            presenceMonitor.reachabilityEpochMs.collect { drainEligible() }
        }
        scope.launch {
            presenceMonitor.onlineSnapshotEpochMs.collect { drainEligible() }
        }
    }

    /**
     * Send to LAN-reachable peers now; queue the rest until they return to local Wi‑Fi.
     */
    suspend fun sendOrQueue(
        sources: List<MultiCopySource>,
        selectedDevices: List<MultiCopyDeviceOption>,
        skipTransferPrepare: Boolean = false
    ): QueueAwareSendResult {
        transferManager.awaitReady()
        require(sources.isNotEmpty()) { "Select at least one file" }
        require(selectedDevices.isNotEmpty()) { "Select at least one destination device" }

        val localDevices = selectedDevices.filter { it.isLocal }
        val remoteDevices = selectedDevices.filter { !it.isLocal }

        if (remoteDevices.isEmpty()) {
            val batch = transferManager.sendToDevices(sources, selectedDevices, skipTransferPrepare)
            return QueueAwareSendResult(batch, emptyList(), batch.summaryMessage)
        }

        val (routable, blocked) = partitionByLanReachability(remoteDevices)
        val sendNow = localDevices + routable
        val batch = if (sendNow.isNotEmpty()) {
            transferManager.sendToDevices(sources, sendNow, skipTransferPrepare = true)
        } else {
            null
        }

        val queuedNames = if (blocked.isNotEmpty()) {
            enqueueSourcesInternal(sources, blocked.map { it.deviceId })
        } else {
            emptyList()
        }

        return buildResult(batch, queuedNames, sendNow.isNotEmpty())
    }

    suspend fun sendLocalPathsOrQueue(
        absolutePaths: List<String>,
        deviceIds: List<String>,
        skipTransferPrepare: Boolean = false
    ): QueueAwareSendResult {
        transferManager.awaitReady()
        val sources = LocalTransferTree.expandAbsolutePaths(absolutePaths)
        check(sources.isNotEmpty()) { "Nothing to send — empty folder or missing files" }
        val options = transferManager.resolveRemoteDeviceOptions(deviceIds)
        return sendOrQueue(sources, options, skipTransferPrepare)
    }

    suspend fun enqueueLocalPaths(absolutePaths: List<String>, deviceIds: List<String>) {
        transferManager.awaitReady()
        require(absolutePaths.isNotEmpty()) { "Nothing to queue" }
        require(deviceIds.isNotEmpty()) { "Select at least one destination device" }
        val names = deviceNames(deviceIds)
        val label = buildDisplayLabel(pathSummary(absolutePaths), names)
        insertEntity(
            sourceKind = QueuedTransferSourceKind.LocalRoots,
            sourceJson = json.encodeToString(absolutePaths),
            deviceIds = deviceIds,
            displayLabel = label
        )
    }

    suspend fun remove(id: String) {
        dao.deleteById(id)
    }

    suspend fun drainEligible() {
        if (!isActiveLanConnectivity()) return
        if (TransferActivityGuard.isTransferActive()) return
        drainMutex.withLock {
            val queued = dao.listByStatus(PendingTransferStatus.Queued.name)
            for (entity in queued) {
                drainOne(entity)
            }
        }
    }

    private suspend fun drainOne(entity: PendingTransferEntity) {
        val pendingIds = decodeDeviceIds(entity.pendingDeviceIdsJson)
        if (pendingIds.isEmpty()) {
            dao.deleteById(entity.id)
            return
        }
        val routableIds = pendingIds.filter { deviceId ->
            val peer = deviceRepository.getDevice(deviceId) ?: return@filter false
            presenceMonitor.quickAssessLanReachability(peer).isDirect
        }
        if (routableIds.isEmpty()) return

        val sources = decodeSources(entity) ?: run {
            dao.deleteById(entity.id)
            return
        }
        if (sources.isEmpty()) {
            dao.deleteById(entity.id)
            return
        }

        dao.upsert(
            entity.copy(
                status = PendingTransferStatus.Sending.name,
                lastAttemptEpochMs = TimeUtils.now(),
                attemptCount = entity.attemptCount + 1
            )
        )

        val options = transferManager.resolveRemoteDeviceOptions(routableIds)
        val batch = runCatching {
            transferManager.sendToDevices(sources, options, skipTransferPrepare = true)
        }.getOrElse { error ->
            dao.upsert(
                entity.copy(
                    status = PendingTransferStatus.Queued.name,
                    lastError = error.message,
                    lastAttemptEpochMs = TimeUtils.now()
                )
            )
            return
        }

        val succeeded = batch.results.flatMap { it.succeededDeviceIds }.toSet()
        val failedIds = routableIds.filter { id ->
            id !in succeeded && batch.results.any { id in it.failures }
        }
        val stillPending = (pendingIds - routableIds.toSet()) + failedIds

        if (stillPending.isEmpty()) {
            dao.deleteById(entity.id)
        } else {
            val names = deviceNames(stillPending)
            dao.upsert(
                entity.copy(
                    status = PendingTransferStatus.Queued.name,
                    pendingDeviceIdsJson = json.encodeToString(stillPending),
                    displayLabel = buildDisplayLabel(sourceSummaryFromEntity(entity), names),
                    lastError = batch.summaryMessage.takeIf { failedIds.isNotEmpty() }
                )
            )
        }
    }

    private suspend fun partitionByLanReachability(
        remoteDevices: List<MultiCopyDeviceOption>
    ): Pair<List<MultiCopyDeviceOption>, List<MultiCopyDeviceOption>> {
        val routable = mutableListOf<MultiCopyDeviceOption>()
        val blocked = mutableListOf<MultiCopyDeviceOption>()
        for (device in remoteDevices) {
            val peer = deviceRepository.getDevice(device.deviceId)
            if (peer == null) {
                blocked += device
                continue
            }
            when (val verdict = presenceMonitor.quickAssessLanReachability(peer)) {
                is PeerLanReachabilityVerdict.Direct -> {
                    routable += device.copy(host = verdict.host, port = verdict.port)
                }
                else -> blocked += device
            }
        }
        return routable to blocked
    }

    private suspend fun enqueueSourcesInternal(
        sources: List<MultiCopySource>,
        deviceIds: List<String>
    ): List<String> {
        if (deviceIds.isEmpty()) return emptyList()
        val names = deviceNames(deviceIds)
        val snapshots = sources.map { it.toSnapshot() }
        val label = buildDisplayLabel(fileSummary(sources), names)
        insertEntity(
            sourceKind = QueuedTransferSourceKind.Sources,
            sourceJson = json.encodeToString(snapshots),
            deviceIds = deviceIds,
            displayLabel = label
        )
        return names
    }

    private suspend fun insertEntity(
        sourceKind: QueuedTransferSourceKind,
        sourceJson: String,
        deviceIds: List<String>,
        displayLabel: String
    ) {
        dao.upsert(
            PendingTransferEntity(
                id = generateDeviceId(),
                createdAtEpochMs = TimeUtils.now(),
                status = PendingTransferStatus.Queued.name,
                sourceKind = sourceKind.name,
                sourceJson = sourceJson,
                pendingDeviceIdsJson = json.encodeToString(deviceIds),
                displayLabel = displayLabel
            )
        )
    }

    private suspend fun deviceNames(deviceIds: List<String>): List<String> =
        deviceIds.map { id ->
            deviceRepository.getDevice(id)?.deviceName ?: id
        }

    private fun buildResult(
        batch: TransferBatchResult?,
        queuedNames: List<String>,
        hadImmediateTargets: Boolean
    ): QueueAwareSendResult {
        val message = when {
            queuedNames.isEmpty() -> batch?.summaryMessage ?: "Send complete"
            !hadImmediateTargets -> queueOnlyMessage(queuedNames)
            else -> {
                val sentPart = batch?.summaryMessage ?: "Sent"
                val queuePart = queueOnlyMessage(queuedNames)
                "$sentPart ${queuePart.replaceFirstChar { it.lowercase() }}"
            }
        }
        return QueueAwareSendResult(batch, queuedNames, message)
    }

    private fun queueOnlyMessage(deviceNames: List<String>): String =
        PeerReachabilityMessages.fileTransferOffWifiQueuedMultiple(deviceNames)

    private fun PendingTransferEntity.toUiItem(): PendingTransferItem? {
        if (status != PendingTransferStatus.Queued.name) return null
        val pendingIds = decodeDeviceIds(pendingDeviceIdsJson)
        val targetLabel = displayLabel.substringAfter(" → ", "devices")
        return PendingTransferItem(
            id = id,
            createdAtEpochMs = createdAtEpochMs,
            displayLabel = displayLabel,
            pendingDeviceIds = pendingIds,
            pendingDeviceNames = listOf(targetLabel),
            sourceSummary = sourceSummaryFromEntity(this),
            lastError = lastError
        )
    }

    private fun sourceSummaryFromEntity(entity: PendingTransferEntity): String =
        when (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull()) {
            QueuedTransferSourceKind.LocalRoots -> {
                val paths = runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), entity.sourceJson)
                }.getOrDefault(emptyList())
                pathSummary(paths)
            }
            QueuedTransferSourceKind.Sources -> {
                val snapshots = runCatching {
                    json.decodeFromString(ListSerializer(QueuedSourceSnapshot.serializer()), entity.sourceJson)
                }.getOrDefault(emptyList())
                fileSummary(snapshots.map { it.toSource() })
            }
            null -> entity.displayLabel
        }

    private fun decodeDeviceIds(jsonPayload: String): List<String> =
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), jsonPayload)
        }.getOrDefault(emptyList())

    private fun decodeSources(entity: PendingTransferEntity): List<MultiCopySource>? =
        when (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull()) {
            QueuedTransferSourceKind.LocalRoots -> {
                val paths = runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), entity.sourceJson)
                }.getOrNull() ?: return null
                LocalTransferTree.expandAbsolutePaths(paths).map { it as MultiCopySource }
            }
            QueuedTransferSourceKind.Sources -> {
                val snapshots = runCatching {
                    json.decodeFromString(ListSerializer(QueuedSourceSnapshot.serializer()), entity.sourceJson)
                }.getOrNull() ?: return null
                snapshots.map { it.toSource() }
            }
            null -> null
        }

    private fun pathSummary(paths: List<String>): String =
        when (paths.size) {
            0 -> "Files"
            1 -> paths.first().substringAfterLast('/').substringAfterLast('\\')
            else -> "${paths.size} items"
        }

    private fun fileSummary(sources: List<MultiCopySource>): String =
        when (sources.size) {
            0 -> "Files"
            1 -> sources.first().fileName
            else -> "${sources.size} files"
        }

    private fun buildDisplayLabel(sourcePart: String, deviceNames: List<String>): String {
        val targetPart = when (deviceNames.size) {
            0 -> "devices"
            1 -> deviceNames.first()
            else -> "${deviceNames.size} devices"
        }
        return "$sourcePart → $targetPart"
    }
}

private fun MultiCopySource.toSnapshot(): QueuedSourceSnapshot =
    when (this) {
        is MultiCopySource.Local -> QueuedSourceSnapshot(
            fileName = fileName,
            sizeBytes = sizeBytes,
            absolutePath = absolutePath,
            relativeDestPath = relativeDestPath
        )
        is MultiCopySource.Remote -> QueuedSourceSnapshot(
            fileName = fileName,
            sizeBytes = sizeBytes,
            absolutePath = absolutePath,
            relativeDestPath = relativeDestPath,
            remoteHost = host,
            remotePort = port
        )
    }

private fun QueuedSourceSnapshot.toSource(): MultiCopySource {
    val host = remoteHost?.trim().orEmpty()
    return if (host.isNotEmpty() && remotePort != null) {
        MultiCopySource.Remote(
            fileName = fileName,
            sizeBytes = sizeBytes,
            absolutePath = absolutePath,
            host = host,
            port = remotePort,
            relativeDestPath = relativeDestPath
        )
    } else {
        MultiCopySource.Local(
            fileName = fileName,
            sizeBytes = sizeBytes,
            absolutePath = absolutePath,
            relativeDestPath = relativeDestPath
        )
    }
}
