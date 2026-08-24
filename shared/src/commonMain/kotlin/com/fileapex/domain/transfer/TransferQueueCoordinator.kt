package com.fileapex.domain.transfer

import com.fileapex.cloud.drive.DriveRelayCoordinator
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.cloud.drive.driveLog
import com.fileapex.cloud.drive.driveLogError
import com.fileapex.cloud.drive.startDriveGrantIfNeeded
import com.fileapex.platform.DriveRelayNotifier
import com.fileapex.data.db.PendingTransferDao
import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.PendingTransferStatus
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.QueuedSourceSnapshot
import com.fileapex.data.db.QueuedTransferSourceKind
import com.fileapex.i18n.AppI18n
import com.fileapex.data.device.DeviceRepository
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.domain.presence.PeerLanReachabilityVerdict
import com.fileapex.domain.presence.PeerPresenceMonitor
import com.fileapex.network.PeerReachabilityMessages
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val lastError: String?,
    val isSending: Boolean = false
)

/**
 * Outcome of [TransferQueueCoordinator.sendOrQueue] — immediate send plus optional queue.
 */
data class QueueAwareSendResult(
    val batch: TransferBatchResult?,
    val queuedDeviceNames: List<String>,
    val message: String,
    val relayedDeviceNames: List<String> = emptyList(),
    val pendingDesktopSyncNames: List<String> = emptyList(),
    val needsCellularConfirm: Boolean = false
) {
    val hadImmediateSend: Boolean get() = batch != null && !batch.allFailed
    val hadQueue: Boolean get() = queuedDeviceNames.isNotEmpty()
    val hadRelay: Boolean get() = relayedDeviceNames.isNotEmpty()
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
    private var pendingDrainJob: Job? = null

    val pendingItems: Flow<List<PendingTransferItem>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toUiItem() } }

    /** Queued + in-flight drain attempts — keeps the header badge stable while sending. */
    val pendingCount: Flow<Int> = dao.observeAll().map { it.size }

    fun ensureDrainWatcher() {
        if (drainWatcherStarted) return
        drainWatcherStarted = true
        scope.launch {
            presenceMonitor.reachabilityEpochMs.collect { requestDrain() }
        }
        scope.launch {
            presenceMonitor.onlineSnapshotEpochMs.collect { requestDrain() }
        }
    }

    fun scheduleDrain() {
        requestDrain()
    }

    private fun requestDrain() {
        pendingDrainJob?.cancel()
        pendingDrainJob = scope.launch {
            delay(DRAIN_TRIGGER_DEBOUNCE_MS)
            drainEligible()
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
        require(sources.isNotEmpty()) { AppI18n.t("select_at_least_one_file") }
        require(selectedDevices.isNotEmpty()) { AppI18n.t("select_destination_device") }

        val localDevices = selectedDevices.filter { it.isLocal }
        val remoteDevices = selectedDevices.filter { !it.isLocal }

        if (remoteDevices.isEmpty()) {
            val batch = transferManager.sendToDevices(sources, selectedDevices, skipTransferPrepare)
            return QueueAwareSendResult(batch, emptyList(), batch.summaryMessage)
        }

        val (routable, blocked) = partitionByLanReachability(remoteDevices)
        val sendNow = localDevices + routable
        val batch = if (sendNow.isNotEmpty()) {
            runCatching {
                transferManager.sendToDevices(sources, sendNow, skipTransferPrepare = false)
            }.getOrNull()
        } else {
            null
        }

        val succeeded = batch?.results?.flatMap { it.succeededDeviceIds }?.toSet().orEmpty()
        val failedRoutableIds = routable.map { it.deviceId }.filter { it !in succeeded }
        val offLanIds = (blocked.map { it.deviceId } + failedRoutableIds).distinct()
        val queuedNames = enqueueSourcesInternal(sources, offLanIds)
        return buildResult(
            batch = batch?.takeIf { succeeded.isNotEmpty() },
            queuedNames = queuedNames,
            hadImmediateTargets = succeeded.isNotEmpty(),
            relayedNames = emptyList(),
            pendingDesktopSyncNames = emptyList(),
            queueReason = null
        )
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
        require(deviceIds.isNotEmpty()) { AppI18n.t("select_destination_device") }
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
        val entity = dao.getById(id) ?: return
        deleteQueueItem(entity)
    }

    suspend fun drainEligible() {
        if (TransferActivityGuard.isTransferActive()) return
        drainMutex.withLock {
            recoverStaleSendingRows()
            val queued = dao.listByStatus(PendingTransferStatus.Queued.name)
            for (entity in queued) {
                drainOne(entity)
            }
        }
    }

    private suspend fun recoverStaleSendingRows() {
        if (TransferActivityGuard.isTransferActive()) return
        val sending = dao.listByStatus(PendingTransferStatus.Sending.name)
        for (entity in sending) {
            dao.upsert(
                entity.copy(
                    status = PendingTransferStatus.Queued.name,
                    lastError = entity.lastError ?: "Send did not finish — retrying"
                )
            )
        }
    }

    private suspend fun resolveDrainTargets(
        pendingIds: List<String>
    ): List<Pair<String, MultiCopyDeviceOption>> = buildList {
        for (deviceId in pendingIds) {
            val peer = deviceRepository.getDevice(deviceId) ?: continue
            val endpoint = resolveTransferEndpoint(peer) ?: continue
            deviceRepository.touchPeerLastSeen(deviceId, endpoint.first, endpoint.second)
            val option = runCatching {
                transferManager.resolveRemoteDeviceOptionsAtEndpoint(
                    deviceIds = listOf(deviceId),
                    host = endpoint.first,
                    port = endpoint.second
                ).firstOrNull()
            }.getOrNull() ?: continue
            add(deviceId to option)
        }
    }

    private suspend fun drainOne(entity: PendingTransferEntity) {
        val now = TimeUtils.now()
        val pendingIds = decodeDeviceIds(entity.pendingDeviceIdsJson)
        if (pendingIds.isEmpty()) {
            deleteQueueItem(entity)
            return
        }
        val sources = decodeSources(entity) ?: run {
            deleteQueueItem(entity)
            return
        }
        if (sources.isEmpty()) {
            deleteQueueItem(entity)
            return
        }

        val driveReady = DriveRelayPolicy.ensureReadyForSend() && DriveRelayPolicy.canSend()
        val reachabilityMap = presenceMonitor.reachabilityEpochMs.value
        val hasFreshSignal = pendingIds.any { id ->
            (reachabilityMap[id] ?: 0L) > entity.lastAttemptEpochMs
        }
        val pendingOnlineOrFresh = hasFreshSignal || pendingIds.any { id ->
            deviceRepository.getDevice(id)?.let { presenceMonitor.isDeviceOnline(it) } == true
        }
        val grantJustCompleted = isWaitingDriveGrant(entity.lastError) && driveReady
        if (entity.lastAttemptEpochMs > 0L &&
            now - entity.lastAttemptEpochMs < DRAIN_RETRY_BACKOFF_MS &&
            !pendingOnlineOrFresh &&
            !grantJustCompleted
        ) {
            return
        }

        val routableTargets = if (isActiveLanConnectivity()) {
            resolveDrainTargets(pendingIds)
        } else {
            emptyList()
        }
        val routableIds = routableTargets.map { it.first }
        val offLanIds = pendingIds.filter { it !in routableIds.toSet() }
        if (routableTargets.isEmpty() && offLanIds.isEmpty()) {
            return
        }

        dao.upsert(
            entity.copy(
                status = PendingTransferStatus.Sending.name,
                lastAttemptEpochMs = TimeUtils.now(),
                attemptCount = entity.attemptCount + 1
            )
        )

        val delivered = mutableSetOf<String>()
        var lastError: String? = null

        if (offLanIds.isNotEmpty()) {
            driveLog("queue drain via Drive for ${offLanIds.size} off-LAN destination(s)")
            val outcome = relayOrQueueOffLan(sources, offLanIds)
            delivered += outcome.relayedIds
            if (outcome.queueReason == "drive_not_ready") {
                startDriveGrantIfNeeded()
                lastError = WAITING_DRIVE_GRANT
            } else if (outcome.queueIds.isNotEmpty()) {
                lastError = AppI18n.t("drive_relay_did_not_finish")
            }
        }

        if (routableTargets.isNotEmpty()) {
            val batch = runCatching {
                transferManager.sendToDevices(
                    sources,
                    routableTargets.map { it.second },
                    skipTransferPrepare = false
                )
            }.getOrElse { error ->
                lastError = error.message
                null
            }
            if (batch != null) {
                val succeeded = batch.results.flatMap { it.succeededDeviceIds }.toSet()
                delivered += routableIds.filter { it in succeeded }
                val failedIds = routableIds.filter { id ->
                    id !in succeeded && batch.results.any { id in it.failures }
                }
                if (failedIds.isNotEmpty()) {
                    lastError = batch.summaryMessage
                }
            }
        }

        val stillPending = pendingIds.filter { it !in delivered }
        if (stillPending.isEmpty()) {
            deleteQueueItem(entity)
            return
        }
        val names = deviceNames(stillPending)
        dao.upsert(
            entity.copy(
                status = PendingTransferStatus.Queued.name,
                pendingDeviceIdsJson = json.encodeToString(stillPending),
                displayLabel = buildDisplayLabel(sourceSummaryFromEntity(entity), names),
                lastError = lastError,
                lastAttemptEpochMs = now,
                attemptCount = entity.attemptCount + 1
            )
        )
        if (!isWaitingDriveGrant(lastError) && offLanIds.any { it in stillPending }) {
            scope.launch {
                delay(DRAIN_RETRY_BACKOFF_MS)
                scheduleDrain()
            }
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
            val endpoint = resolveTransferEndpoint(peer)
            if (endpoint != null) {
                routable += device.copy(host = endpoint.first, port = endpoint.second)
            } else {
                blocked += device
            }
        }
        return routable to blocked
    }

    private suspend fun resolveTransferEndpoint(peer: PairedDeviceEntity): Pair<String, Int>? {
        val direct = presenceMonitor.quickAssessLanReachability(peer) as? PeerLanReachabilityVerdict.Direct
            ?: return null
        return direct.host to direct.port
    }

    suspend fun enqueueSources(
        sources: List<MultiCopySource>,
        deviceIds: List<String>
    ): List<String> = enqueueSourcesInternal(sources, deviceIds)

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
        scheduleDrain()
    }

    private suspend fun deviceNames(deviceIds: List<String>): List<String> =
        deviceIds.map { id ->
            deviceRepository.getDevice(id)?.deviceName ?: id
        }

    private data class OffLanRelayOutcome(
        val relayedNames: List<String>,
        val relayedIds: List<String> = emptyList(),
        val queueIds: List<String>,
        val desktopPendingNames: List<String>,
        val queueReason: String? = null
    )

    private suspend fun relayOrQueueOffLan(
        sources: List<MultiCopySource>,
        deviceIds: List<String>
    ): OffLanRelayOutcome {
        if (deviceIds.isEmpty()) {
            return OffLanRelayOutcome(emptyList(), emptyList(), emptyList(), emptyList())
        }
        if (!DriveRelayPolicy.ensureReadyForSend()) {
            startDriveGrantIfNeeded()
            return OffLanRelayOutcome(
                relayedNames = emptyList(),
                queueIds = deviceIds,
                desktopPendingNames = emptyList(),
                queueReason = "drive_not_ready"
            )
        }
        if (DriveRelayPolicy.needsSendPrompt()) {
            DriveRelayCoordinator.requestSendConfirmation(sources, deviceIds)
            return OffLanRelayOutcome(emptyList(), emptyList(), emptyList(), emptyList())
        }
        if (!DriveRelayPolicy.canSend()) {
            startDriveGrantIfNeeded()
            return OffLanRelayOutcome(
                relayedNames = emptyList(),
                queueIds = deviceIds,
                desktopPendingNames = emptyList(),
                queueReason = "drive_not_ready"
            )
        }
        if (DriveRelayPolicy.payloadExceedsRelayLimit(sources.map { it.sizeBytes })) {
            return OffLanRelayOutcome(
                relayedNames = emptyList(),
                queueIds = deviceIds,
                desktopPendingNames = emptyList(),
                queueReason = "relay_too_large"
            )
        }
        val localSources = sources.filter { it is MultiCopySource.Local && it.absolutePath.isNotBlank() }
        if (localSources.isEmpty()) {
            return OffLanRelayOutcome(emptyList(), emptyList(), deviceIds, emptyList())
        }
        return runCatching {
            val entries = DriveRelayCoordinator.uploadDirectTransfers(localSources, deviceIds)
            val names = deviceNames(deviceIds)
            DriveRelayNotifier.notifyPosted(
                fileNames = entries.map { it.fileName }.distinct(),
                targetNames = names
            )
            OffLanRelayOutcome(
                relayedNames = names,
                relayedIds = deviceIds,
                queueIds = emptyList(),
                desktopPendingNames = desktopNamesAmong(deviceIds)
            )
        }.getOrElse { error ->
            driveLogError("Drive relay failed - queuing", error)
            DriveRelayNotifier.notifyFailed(
                fileName = localSources.firstOrNull()?.fileName.orEmpty(),
                queued = true
            )
            OffLanRelayOutcome(emptyList(), emptyList(), deviceIds, emptyList())
        }
    }

    private suspend fun desktopNamesAmong(deviceIds: List<String>): List<String> =
        deviceIds.mapNotNull { id ->
            val peer = deviceRepository.getDevice(id) ?: return@mapNotNull null
            if (PeerPlatform.isDesktop(peer.os, peer.platform)) peer.deviceName else null
        }

    private fun buildResult(
        batch: TransferBatchResult?,
        queuedNames: List<String>,
        hadImmediateTargets: Boolean,
        relayedNames: List<String> = emptyList(),
        pendingDesktopSyncNames: List<String> = emptyList(),
        queueReason: String? = null
    ): QueueAwareSendResult {
        val sentPart = batch?.summaryMessage ?: AppI18n.t("sent")
        val relayPart = relayMessage(relayedNames, pendingDesktopSyncNames)
        val queuedPart = when (queueReason) {
            "drive_not_ready" ->
                PeerReachabilityMessages.fileTransferOffWifiDriveNotReady(queuedNames)
            "relay_too_large" ->
                AppI18n.t(
                    "relay_too_large_queued",
                    DriveRelayPolicy.relayLimitLabel(),
                    queuedNames.joinToString(", ")
                )
            else -> queueOnlyMessage(queuedNames)
        }
        val needsConfirm = queuedNames.isEmpty() && relayedNames.isEmpty() &&
            DriveRelayCoordinator.pendingSendPrompt.value
        val message = when {
            needsConfirm -> AppI18n.t("confirm_cellular_send")
            queuedNames.isEmpty() && relayedNames.isEmpty() -> sentPart
            relayedNames.isNotEmpty() && queuedNames.isEmpty() && !hadImmediateTargets ->
                relayPart
            relayedNames.isNotEmpty() && queuedNames.isEmpty() ->
                "$sentPart $relayPart"
            !hadImmediateTargets -> queuedPart
            else -> "$sentPart ${queuedPart.replaceFirstChar { it.lowercase() }}"
        }
        return QueueAwareSendResult(
            batch = batch,
            queuedDeviceNames = queuedNames,
            message = message,
            relayedDeviceNames = relayedNames,
            pendingDesktopSyncNames = pendingDesktopSyncNames,
            needsCellularConfirm = needsConfirm
        )
    }

    private fun relayMessage(relayedNames: List<String>, desktopNames: List<String>): String {
        val sent = AppI18n.t("sent_via_drive_to", relayedNames.joinToString(", "))
        if (desktopNames.isEmpty()) return sent
        return "$sent. ${AppI18n.t("waiting_desktop_drive_sync")}"
    }

    private fun queueOnlyMessage(deviceNames: List<String>): String =
        PeerReachabilityMessages.fileTransferOffWifiQueuedMultiple(deviceNames)

    private fun PendingTransferEntity.toUiItem(): PendingTransferItem? {
        val statusEnum = runCatching { PendingTransferStatus.valueOf(status) }.getOrNull()
            ?: return null
        if (statusEnum != PendingTransferStatus.Queued && statusEnum != PendingTransferStatus.Sending) {
            return null
        }
        val pendingIds = decodeDeviceIds(pendingDeviceIdsJson)
        val targetLabel = displayLabel.substringAfter(" → ", "devices")
        return PendingTransferItem(
            id = id,
            createdAtEpochMs = createdAtEpochMs,
            displayLabel = displayLabel,
            pendingDeviceIds = pendingIds,
            pendingDeviceNames = listOf(targetLabel),
            sourceSummary = sourceSummaryFromEntity(this),
            lastError = localizeQueueError(lastError),
            isSending = statusEnum == PendingTransferStatus.Sending
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

    private suspend fun deleteQueueItem(entity: PendingTransferEntity) {
        ShareStagingCleanup.deleteSessionRootsForPaths(absolutePathsFromEntity(entity))
        dao.deleteById(entity.id)
    }

    private fun absolutePathsFromEntity(entity: PendingTransferEntity): List<String> =
        when (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull()) {
            QueuedTransferSourceKind.LocalRoots -> {
                runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), entity.sourceJson)
                }.getOrDefault(emptyList())
            }
            QueuedTransferSourceKind.Sources -> {
                runCatching {
                    json.decodeFromString(
                        ListSerializer(QueuedSourceSnapshot.serializer()),
                        entity.sourceJson
                    )
                }.getOrDefault(emptyList()).map { it.absolutePath }
            }
            null -> emptyList()
        }

    companion object {
        private const val DRAIN_TRIGGER_DEBOUNCE_MS = 750L
        private const val DRAIN_RETRY_BACKOFF_MS = 30_000L
        private const val WAITING_DRIVE_GRANT = "waiting_drive_grant"
        private const val WAITING_DRIVE_GRANT_LEGACY = "Waiting for Google Drive grant"

        private fun isWaitingDriveGrant(error: String?): Boolean =
            error == WAITING_DRIVE_GRANT || error == WAITING_DRIVE_GRANT_LEGACY

        private fun localizeQueueError(raw: String?): String? = when (raw) {
            null, "" -> raw
            WAITING_DRIVE_GRANT, WAITING_DRIVE_GRANT_LEGACY -> AppI18n.t("waiting_drive_grant")
            "Drive relay did not finish" -> AppI18n.t("drive_relay_did_not_finish")
            else -> raw
        }
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
