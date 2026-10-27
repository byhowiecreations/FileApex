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
import com.fileapex.data.db.QueuedTransferSourceKind
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.data.device.DeviceRepository
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.domain.presence.RouteUse
import com.fileapex.domain.presence.PeerPresenceMonitor
import com.fileapex.network.TransferCancelledException
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.fileapex.platform.generateDeviceId
import com.fileapex.domain.transfer.QueuedTransferSources.absolutePathsFromEntity
import com.fileapex.domain.transfer.QueuedTransferSources.buildDisplayLabel
import com.fileapex.domain.transfer.QueuedTransferSources.changedSourceName
import com.fileapex.domain.transfer.QueuedTransferSources.decodeDeviceIds
import com.fileapex.domain.transfer.QueuedTransferSources.decodeSources
import com.fileapex.domain.transfer.QueuedTransferSources.fileSummary
import com.fileapex.domain.transfer.QueuedTransferSources.pathSummary
import com.fileapex.domain.transfer.QueuedTransferSources.sourceSummaryFromEntity
import com.fileapex.domain.transfer.QueuedTransferSources.toSnapshot

data class PendingTransferItem(
    val id: String,
    val createdAtEpochMs: Long,
    val displayLabel: String,
    val pendingDeviceIds: List<String>,
    val pendingDeviceNames: List<String>,
    val sourceSummary: String,
    val lastError: String?,
    val isSending: Boolean = false,
    val isPaused: Boolean = false
)

/**
 * Outcome of [TransferQueueCoordinator.sendOrQueue], immediate send plus optional queue.
 */
data class QueueAwareSendResult(
    val batch: TransferBatchResult?,
    val queuedDeviceNames: List<String>,
    val message: String,
    val relayedDeviceNames: List<String> = emptyList(),
    val needsCellularConfirm: Boolean = false
) {
    val hadImmediateSend: Boolean get() = batch != null && !batch.allFailed
    val hadQueue: Boolean get() = queuedDeviceNames.isNotEmpty()
    val hadRelay: Boolean get() = relayedDeviceNames.isNotEmpty()
}

/**
 * Persists deferred outbound transfers (source paths only) and drains them when peers
 * become reachable on any route. All sends still go through [TransferManager].
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
    /** Rows with a send in flight; each runs on its own so a slow one never holds up the rest. */
    private val claimedRows = ConcurrentHashMap.newKeySet<String>()
    private val sendingJobs = ConcurrentHashMap<String, Job>()
    private val removalRequested = ConcurrentHashMap.newKeySet<String>()

    val pendingItems: Flow<List<PendingTransferItem>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toUiItem() } }

    /** Queued + in-flight drain attempts, keeps the header badge stable while sending. */
    val pendingCount: Flow<Int> = dao.observeAll().map { it.size }

    fun ensureDrainWatcher() {
        if (drainWatcherStarted) return
        drainWatcherStarted = true
        scope.launch {
            presenceMonitor.reachabilityEpochMs.collect { requestDrain() }
        }
        scope.launch {
            presenceMonitor.onlineDeviceIds.collect { requestDrain() }
        }
        scope.launch {
            TransferActivityGuard.statsFlow
                .map { it.isActive }
                .distinctUntilChanged()
                .filter { active -> !active }
                .collect { requestDrain() }
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

    /** A send the user just asked for starts at once; only background triggers wait out the debounce. */
    private fun drainNow() {
        scope.launch { drainEligible() }
    }

    /**
     * Send to reachable peers now (LAN, Tailscale or Iroh); queue the rest until a route answers.
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

        val localBatch = if (localDevices.isNotEmpty()) {
            transferManager.sendToDevices(sources, localDevices, skipTransferPrepare)
        } else {
            null
        }
        // Remote sends always go through the queue: it persists across restarts, retries, and starts
        // right away when the peer is reachable, so the caller never has to wait on the transfer.
        val queuedNames = enqueueSourcesInternal(sources, remoteDevices.map { it.deviceId })
        return QueueAwareSendResult(
            batch = localBatch,
            queuedDeviceNames = queuedNames,
            message = AppI18n.t("queued_sending_background", queuedNames.joinToString(", "))
        )
    }

    suspend fun sendLocalPathsOrQueue(
        absolutePaths: List<String>,
        deviceIds: List<String>,
        skipTransferPrepare: Boolean = false
    ): QueueAwareSendResult {
        transferManager.awaitReady()
        val sources = LocalTransferTree.expandAbsolutePaths(absolutePaths)
        check(sources.isNotEmpty()) { AppI18n.t("nothing_to_send_empty_folder") }
        val options = transferManager.resolveRemoteDeviceOptionsForImmediateSend(deviceIds)
        return sendOrQueue(sources, options, skipTransferPrepare)
    }

    suspend fun enqueueLocalPaths(absolutePaths: List<String>, deviceIds: List<String>) {
        transferManager.awaitReady()
        require(absolutePaths.isNotEmpty()) { AppI18n.t("nothing_to_queue") }
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

    /** A row that is sending is cancelled first; its staging files are deleted once the send unwinds. */
    suspend fun remove(id: String) {
        val entity = dao.getById(id) ?: return
        val job = sendingJobs[id]
        if (job != null) {
            removalRequested += id
            TransferActivityGuard.cancelOwner(id)
            job.cancel()
            return
        }
        deleteQueueItem(entity)
    }

    fun cancelSending(id: String): Boolean {
        val job = sendingJobs[id] ?: return false
        TransferActivityGuard.cancelOwner(id)
        job.cancel()
        return true
    }

    /** Re-queues a paused or failed row and drains immediately, skipping the retry backoff. */
    suspend fun retryNow(id: String) {
        val entity = dao.getById(id) ?: return
        if (entity.status == PendingTransferStatus.Sending.name) return
        dao.upsert(
            entity.copy(
                status = PendingTransferStatus.Queued.name,
                lastError = null,
                lastAttemptEpochMs = 0L
            )
        )
        scheduleDrain()
    }

    /**
     * Claims every queued row and starts each on its own coroutine. Returns once they are started, so
     * a new row never waits behind a slow one; [TransferRuntime.streamBudget] still caps the sockets.
     */
    suspend fun drainEligible() {
        drainMutex.withLock {
            expireOldRows()
            recoverStaleSendingRows()
            for (entity in dao.listByStatus(PendingTransferStatus.Queued.name)) {
                if (!claimedRows.add(entity.id)) continue
                scope.launch {
                    try {
                        drainOne(entity)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        println("TransferQueue: row ${entity.id} failed - ${error.message}")
                    } finally {
                        claimedRows.remove(entity.id)
                    }
                }
            }
        }
    }

    /** A row that never got delivered stops waiting after a day; the user can still cancel earlier. */
    private suspend fun expireOldRows() {
        val cutoff = TimeUtils.now() - QUEUE_MAX_AGE_MS
        for (entity in dao.listCreatedBefore(cutoff)) {
            if (entity.id in claimedRows) continue
            println("TransferQueue: expired queued item ${entity.id} after 24 hours")
            deleteQueueItem(entity)
        }
    }

    private suspend fun recoverStaleSendingRows() {
        val sending = dao.listByStatus(PendingTransferStatus.Sending.name)
        for (entity in sending) {
            if (entity.id in claimedRows) continue
            dao.upsert(
                entity.copy(
                    status = PendingTransferStatus.Queued.name,
                    lastError = entity.lastError ?: AppI18n.t("send_did_not_finish_retrying")
                )
            )
        }
    }

    private suspend fun resolveDrainTargets(
        pendingIds: List<String>,
        userStarted: Boolean
    ): List<Pair<String, MultiCopyDeviceOption>> = buildList {
        for (deviceId in pendingIds) {
            val peer = deviceRepository.getDevice(deviceId) ?: continue
            val endpoint = resolveTransferEndpoint(peer, userStarted) ?: continue
            deviceRepository.touchPeerLastSeen(deviceId, endpoint.first, endpoint.second)
            val option = cancellableCatching {
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
        val sources = decodeSources(entity) { healed -> scope.launch { dao.upsert(healed) } } ?: run {
            deleteQueueItem(entity)
            return
        }
        if (sources.isEmpty()) {
            deleteQueueItem(entity)
            return
        }
        changedSourceName(entity)?.let { name ->
            dao.upsert(
                entity.copy(
                    status = PendingTransferStatus.Paused.name,
                    lastError = AppI18n.t("queued_source_changed", name),
                    lastAttemptEpochMs = now
                )
            )
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

        // Off the LAN, a tailnet endpoint is still a direct path; Drive is only for peers with none.
        val routableTargets = if (presenceMonitor.canAttemptRoutes()) {
            resolveDrainTargets(pendingIds, TransferQueueRoutePolicy.mayProbeOfflinePeer(entity.lastAttemptEpochMs))
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

        var userCancelled = false
        if (offLanIds.isNotEmpty()) {
            driveLog("queue drain via Drive for ${offLanIds.size} off-LAN destination(s)")
            val outcome = try {
                sendTracked(entity.id) { relayOrQueueOffLan(sources, offLanIds) }
            } catch (cancelled: TransferCancelledException) {
                userCancelled = true
                null
            }
            if (outcome != null) {
                delivered += outcome.relayedIds
                if (outcome.queueReason == "drive_not_ready") {
                    startDriveGrantIfNeeded()
                    lastError = WAITING_DRIVE_GRANT
                } else if (outcome.queueIds.isNotEmpty()) {
                    lastError = AppI18n.t("drive_relay_did_not_finish")
                }
            }
        }

        if (!userCancelled && routableTargets.isNotEmpty()) {
            val batch = try {
                sendTracked(entity.id) {
                    transferManager.sendToDevices(
                        sources,
                        routableTargets.map { it.second },
                        skipTransferPrepare = false
                    )
                }
            } catch (cancelled: TransferCancelledException) {
                userCancelled = true
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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
                for ((id, option) in routableTargets) {
                    if (id in succeeded || batch.results.none { id in it.unreachableDeviceIds }) continue
                    deviceRepository.getDevice(id)?.let { presenceMonitor.reportRouteFailure(it, option.host) }
                }
            }
        }

        val stillPending = pendingIds.filter { it !in delivered }
        if (stillPending.isEmpty() || removalRequested.remove(entity.id)) {
            deleteQueueItem(entity)
            return
        }
        if (userCancelled && TransferActivityGuard.consumeRemoveFromDrive()) {
            deleteQueueItem(entity)
            return
        }
        val names = deviceNames(stillPending)
        if (userCancelled) {
            dao.upsert(
                entity.copy(
                    status = PendingTransferStatus.Paused.name,
                    pendingDeviceIdsJson = json.encodeToString(stillPending),
                    displayLabel = buildDisplayLabel(sourceSummaryFromEntity(entity), names),
                    lastError = USER_CANCELLED,
                    lastAttemptEpochMs = now,
                    attemptCount = entity.attemptCount + 1
                )
            )
            return
        }
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

    private suspend fun <T> sendTracked(id: String, block: suspend () -> T): T = coroutineScope {
        val work = async(TransferOwner(id)) { block() }
        sendingJobs[id] = work
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            if (work.isCancelled && currentCoroutineContext().isActive) throw TransferCancelledException()
            throw cancelled
        } finally {
            sendingJobs.remove(id, work)
        }
    }

    private suspend fun resolveTransferEndpoint(peer: PairedDeviceEntity, userStarted: Boolean): Pair<String, Int>? =
        (if (userStarted) presenceMonitor.probeNow(peer, RouteUse.Bulk) else presenceMonitor.endpointIfOnline(peer, RouteUse.Bulk))
            ?.let { it.host to it.port }

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
        drainNow()
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
        return cancellableCatching {
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
            if (error is CancellationException) throw error
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

    private fun PendingTransferEntity.toUiItem(): PendingTransferItem? {
        val statusEnum = runCatching { PendingTransferStatus.valueOf(status) }.getOrNull()
            ?: return null

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
            isSending = statusEnum == PendingTransferStatus.Sending,
            isPaused = statusEnum == PendingTransferStatus.Paused
        )
    }

    private suspend fun deleteQueueItem(entity: PendingTransferEntity) {
        ShareStagingCleanup.deleteSessionRootsForPaths(absolutePathsFromEntity(entity))
        dao.deleteById(entity.id)
    }

    companion object {
        private const val DRAIN_TRIGGER_DEBOUNCE_MS = 750L
        private const val QUEUE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val DRAIN_RETRY_BACKOFF_MS = 30_000L
        private const val WAITING_DRIVE_GRANT = "waiting_drive_grant"
        private const val USER_CANCELLED = "user_cancelled"
        private const val WAITING_DRIVE_GRANT_LEGACY = "Waiting for Google Drive grant"

        private fun isWaitingDriveGrant(error: String?): Boolean =
            error == WAITING_DRIVE_GRANT || error == WAITING_DRIVE_GRANT_LEGACY

        private fun localizeQueueError(raw: String?): String? = when {
            raw.isNullOrEmpty() -> raw
            isWaitingDriveGrant(raw) -> AppI18n.t("waiting_drive_grant")
            raw == USER_CANCELLED -> AppI18n.t("transfer_cancelled")
            raw == "Drive relay did not finish" -> AppI18n.t("drive_relay_did_not_finish")
            raw == "Nothing to send, empty folder or missing files" ||
                raw == "Nothing to send" -> AppI18n.t("nothing_to_send_empty_folder")
            raw == "Nothing to queue" -> AppI18n.t("nothing_to_queue")
            raw == "Send did not finish, retrying" -> AppI18n.t("send_did_not_finish_retrying")
            raw == "Source read failed" || raw.startsWith("Missing local file") -> AppI18n.t("source_read_failed")
            else -> UserFacingErrors.messageForRaw(raw) ?: raw
        }
    }
}

