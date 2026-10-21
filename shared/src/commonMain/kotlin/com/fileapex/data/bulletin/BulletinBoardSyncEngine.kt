package com.fileapex.data.bulletin

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.RouteUse
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.textContainsWebUrl
import com.fileapex.util.PerKeyRunner
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

class BulletinBoardSyncEngine(
    private val database: BulletinBoardDatabase,
    private val repository: BulletinBoardRepository,
    private val scope: CoroutineScope
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val drainRunner = PerKeyRunner(scope)
    private val pullRunner = PerKeyRunner(scope)
    private val peerBackoff = BulletinPeerBackoff()
    private val messageDao = database.messageDao()
    private val tombstoneDao = database.tombstoneDao()
    private val outboxDao = database.outboxDao()
    private val processedPacketDao = database.processedPacketDao()
    private val transactionDao = database.transactionDao()

    @Volatile
    private var drainWatcherStarted = false
    @Volatile
    private var drainQueued = false
    private var pendingDrainJob: Job? = null
    private var maintenanceJob: Job? = null
    private val drainScheduleLock = Any()

    @Synchronized fun ensureStarted() {
        if (drainWatcherStarted) return
        drainWatcherStarted = true
        scope.launch { cancellableCatching { latestChange() } }
        scope.launch {
            FileApexServices.presenceMonitor.reachabilityEpochMs.collect { requestDrain() }
        }
        scope.launch {
            var previous = emptySet<String>()
            FileApexServices.presenceMonitor.onlineDeviceIds.collect { online ->
                // A device that just came back is tried at once, however long it failed while it was away.
                (online - previous).forEach { peerBackoff.reset(it) }
                previous = online
                requestDrain()
            }
        }
        maintenanceJob = scope.launch {
            while (true) {
                delay(MAINTENANCE_INTERVAL_MS)
                runMaintenance()
            }
        }
    }

    /** An offline peer that can pull a delta gets nothing queued; it asks for what it missed when it is back. */
    private fun shouldQueueFor(peer: PairedDeviceEntity): Boolean =
        BulletinDelta.queueForPeer(
            supportsDelta = BulletinDelta.supportedBy(peer.clientVersion, peer.clientVersionCode),
            isOnline = FileApexServices.presenceMonitor.isDeviceOnline(peer)
        )

    /** Latest change in this board on this device's clock; sent on every health answer. */
    suspend fun latestChange(): Long =
        maxOf(messageDao.latestChange(), tombstoneDao.latestChange()).also { BulletinStampClock.shared.observe(it) }

    /** One page of what changed since [since] (everything when it is 0, stale or from before a reset). */
    suspend fun buildDelta(since: Long, nowMs: Long = TimeUtils.now()): BulletinDeltaResponse {
        val latest = latestChange()
        val snapshot = BulletinDelta.isSnapshot(since, latest, nowMs)
        val from = if (snapshot) 0L else since
        val changes = messageDao.getChangedSince(from).map { message ->
            BulletinDelta.Change(
                message.updatedAt,
                BulletinSyncItem(
                    payloadType = BulletinPayloadType.MESSAGE,
                    payloadId = message.id,
                    body = json.encodeToString(
                        BulletinMessagePayload(
                            id = message.id,
                            originDeviceId = message.originDeviceId,
                            senderName = message.senderName,
                            content = repository.stripRemoteLocalPath(message.content, message.contentType),
                            contentType = message.contentType,
                            timestamp = message.timestamp,
                            isPinned = message.isPinned
                        )
                    )
                )
            )
        } + tombstoneDao.getChangedSince(from).map { tombstone ->
            BulletinDelta.Change(
                tombstone.updatedAt,
                BulletinSyncItem(
                    payloadType = BulletinPayloadType.TOMBSTONE,
                    payloadId = tombstone.id,
                    body = json.encodeToString(
                        BulletinTombstonePayload(
                            id = tombstone.id,
                            deletedAt = tombstone.deletedAt,
                            originDeviceId = tombstone.originDeviceId,
                            remotePurge = tombstone.remotePurge
                        )
                    )
                )
            )
        }
        val page = BulletinDelta.page(changes, from)
        // A finished pull is complete up to the latest change seen before the rows were read.
        val cursor = if (page.more) page.cursor else maxOf(page.cursor, latest)
        return BulletinDeltaResponse(
            batch = BulletinSyncBatch(
                packetId = "delta-" + TimeUtils.now() + "-" + (1000..9999).random(),
                originDeviceId = loadLocalIdentity().deviceId,
                items = page.items
            ),
            cursor = cursor,
            more = page.more,
            snapshot = snapshot
        )
    }

    private val lastPullAttemptMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** A health answer showed [peerVersion]; pull the difference when it is ahead of what we have from that peer. */
    fun onPeerBulletinVersion(peer: PairedDeviceEntity, peerVersion: Long) {
        if (!BulletinDelta.supportedBy(peer.clientVersion, peer.clientVersionCode)) return
        val cursor = FileApexServices.settings.bulletinDeltaCursor(peer.deviceId)
        if (!BulletinDelta.shouldPull(peerVersion, cursor)) return
        val now = TimeUtils.now()
        val last = lastPullAttemptMs[peer.deviceId] ?: 0L
        if (now - last < PULL_RETRY_MS) return
        lastPullAttemptMs[peer.deviceId] = now
        pullRunner.launch(peer.deviceId) {
            cancellableCatching { pullFrom(peer) }
                .onFailure { println("BulletinBoardSyncEngine: delta pull from ${peer.deviceName} failed - ${it.message}") }
        }
    }

    private suspend fun pullFrom(peer: PairedDeviceEntity) {
        val route = FileApexServices.presenceMonitor.endpointIfOnline(peer, RouteUse.Bulk) ?: return
        var cursor = FileApexServices.settings.bulletinDeltaCursor(peer.deviceId)
        repeat(MAX_DELTA_PAGES) {
            val response = FileApexServices.client.fetchBulletinDelta(route.host, route.port, cursor)
            processIncomingBatch(response.batch)
            cursor = response.cursor
            FileApexServices.settings.setBulletinDeltaCursor(peer.deviceId, cursor)
            if (!response.more) return
        }
    }

    suspend fun publishMessage(message: MessageEntity) {
        val peers = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
        val selfId = loadLocalIdentity().deviceId
        val remotePeers = peers.filter { it.deviceId != selfId && BulletinBoardPolicy.takesPartInBulletin(it) }
        val bulletinPeers = remotePeers.filter { it.supportsBulletinSync() && shouldQueueFor(it) }
        val legacyPeers = remotePeers.filterNot { it.supportsBulletinSync() }

        if (bulletinPeers.isNotEmpty()) {
            repository.enqueueOutboxForAllPeers(
                payloadType = BulletinPayloadType.MESSAGE,
                payloadId = message.id,
                peerIds = bulletinPeers.map { it.deviceId }
            )
            bulletinPeers.forEach { peerBackoff.reset(it.deviceId) }
            requestDrain()
        }
        BulletinLegacyRelay.dispatchMessage(message, legacyPeers)
    }

    suspend fun publishTombstone(messageId: String) {
        val peers = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
        val selfId = loadLocalIdentity().deviceId
        val remotePeers = peers.filter { it.deviceId != selfId && BulletinBoardPolicy.takesPartInBulletin(it) }
        val bulletinPeers = remotePeers.filter { it.supportsBulletinSync() && shouldQueueFor(it) }
        val legacyPeers = remotePeers.filterNot { it.supportsBulletinSync() }

        if (bulletinPeers.isNotEmpty()) {
            repository.enqueueOutboxForAllPeers(
                payloadType = BulletinPayloadType.TOMBSTONE,
                payloadId = messageId,
                peerIds = bulletinPeers.map { it.deviceId }
            )
            bulletinPeers.forEach { peerBackoff.reset(it.deviceId) }
            requestDrain()
        }
        FcmWakeCoordinator.dispatchPresenceWakeToLinkedPeers()
        val snapshot = repository.getMessage(messageId)?.toNoteRecord()
        BulletinLegacyRelay.dispatchTombstone(messageId, legacyPeers, snapshot)
    }

    suspend fun publishRetractByKind(originDeviceId: String, contentType: Int, deletedAt: Long) {
        val peers = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
        val selfId = loadLocalIdentity().deviceId
        val remotePeers = peers.filter { it.deviceId != selfId && BulletinBoardPolicy.takesPartInBulletin(it) }
        val bulletinPeers = remotePeers.filter { it.supportsBulletinSync() && shouldQueueFor(it) }
        if (bulletinPeers.isEmpty()) return
        val payload = BulletinRetractByKindPayload(
            originDeviceId = originDeviceId,
            contentType = contentType,
            deletedAt = deletedAt
        )
        val payloadId = BulletinMessageKind.retractPayloadId(originDeviceId, contentType)
        repository.rememberRetractByKind(payload)
        repository.enqueueOutboxForAllPeers(
            payloadType = BulletinPayloadType.RETRACT_BY_KIND,
            payloadId = payloadId,
            peerIds = bulletinPeers.map { it.deviceId }
        )
        bulletinPeers.forEach { peerBackoff.reset(it.deviceId) }
        requestDrain()
        FcmWakeCoordinator.dispatchPresenceWakeToLinkedPeers()
    }

    suspend fun onDevicePairingComplete(peer: PairedDeviceEntity) {
        val selfId = loadLocalIdentity().deviceId
        if (peer.deviceId.isBlank() || peer.deviceId == selfId) return
        if (!BulletinBoardPolicy.takesPartInBulletin(peer)) return
        if (!peer.supportsBulletinSync()) return
        val activeMessages = messageDao.getActiveOnce()
        val recentCutoff = TimeUtils.now() - 7 * 24 * 60 * 60 * 1000L
        val recentTombstones = tombstoneDao.getRecentOnce(recentCutoff)
        val outboxEntries = mutableListOf<OutboxEntity>()
        val now = TimeUtils.now()
        for (msg in activeMessages) {
            outboxEntries += OutboxEntity(
                targetDeviceId = peer.deviceId,
                payloadType = BulletinPayloadType.MESSAGE,
                payloadId = msg.id,
                createdAt = now
            )
        }
        for (ts in recentTombstones) {
            outboxEntries += OutboxEntity(
                targetDeviceId = peer.deviceId,
                payloadType = BulletinPayloadType.TOMBSTONE,
                payloadId = ts.id,
                createdAt = now
            )
        }
        if (outboxEntries.isNotEmpty()) {
            transactionDao.insertOutboxEntries(outboxEntries)
            peerBackoff.reset(peer.deviceId)
            requestDrain()
        }
    }

    suspend fun ingestSharedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val link = textContainsWebUrl(trimmed)
        val message = repository.ingestLocalText(trimmed, link = link)
        publishMessage(message)
    }

    suspend fun ingestSharedFile(absolutePath: String, fileName: String, sizeBytes: Long, caption: String = "") {
        val message = repository.ingestLocalFile(absolutePath, fileName, sizeBytes, caption)
        publishMessage(message)
    }

    suspend fun processIncomingBatch(batch: BulletinSyncBatch): BulletinSyncAck {
        if (processedPacketDao.countById(batch.packetId) > 0) {
            return BulletinSyncAck(
                packetId = batch.packetId,
                originDeviceId = loadLocalIdentity().deviceId,
                acceptedPayloadIds = emptyList()
            )
        }
        val accepted = mutableListOf<String>()
        val incomingMessages = mutableListOf<MessageEntity>()
        val incomingTombstones = mutableListOf<TombstoneEntity>()
        val remotePurgeMessageIds = mutableListOf<String>()
        val kindRetractedMessageIds = mutableListOf<String>()
        for (item in batch.items) {
            if (tombstoneDao.countById(item.payloadId) > 0 && item.payloadType == BulletinPayloadType.MESSAGE) continue
            if (item.retractsForAnUntrustedDevice(json)) { accepted += item.payloadId; continue }
            when (item.payloadType) {
                BulletinPayloadType.MESSAGE -> {
                    val payload = json.decodeFromString<BulletinMessagePayload>(item.body)
                    if (tombstoneDao.countById(payload.id) > 0) continue
                    incomingMessages += MessageEntity(
                        id = payload.id,
                        originDeviceId = payload.originDeviceId,
                        senderName = payload.senderName,
                        content = repository.stripRemoteLocalPath(
                            payload.content,
                            payload.contentType
                        ),
                        contentType = payload.contentType,
                        timestamp = payload.timestamp,
                        isDeleted = false,
                        isPinned = payload.isPinned
                    )
                    accepted += item.payloadId
                }
                BulletinPayloadType.TOMBSTONE -> {
                    val payload = json.decodeFromString<BulletinTombstonePayload>(item.body)
                    incomingTombstones += TombstoneEntity(
                        id = payload.id,
                        deletedAt = payload.deletedAt,
                        originDeviceId = payload.originDeviceId,
                        remotePurge = payload.remotePurge
                    )
                    if (payload.remotePurge) {
                        remotePurgeMessageIds += payload.id
                    }
                    accepted += item.payloadId
                }
                BulletinPayloadType.RETRACT_BY_KIND -> {
                    val payload = json.decodeFromString<BulletinRetractByKindPayload>(item.body)
                    kindRetractedMessageIds += repository.applyRetractByKind(
                        originDeviceId = payload.originDeviceId,
                        contentType = payload.contentType,
                        deletedAt = payload.deletedAt
                    )
                    accepted += item.payloadId
                }
            }
        }
        val boardWasEmpty = messageDao.countActive() == 0
        val newlyArrived = incomingMessages.filter { messageDao.getById(it.id) == null }
        transactionDao.ingestSyncBatch(
            messages = incomingMessages,
            tombstones = incomingTombstones,
            packet = ProcessedPacketEntity(
                packetId = batch.packetId,
                processedAt = TimeUtils.now()
            )
        )
        for (messageId in remotePurgeMessageIds.distinct()) {
            val msg = repository.getMessage(messageId)
            if (msg != null && msg.isPinned) continue
            BulletinRemoteFilePurgeHandler.handle(messageId)
        }
        if (newlyArrived.isNotEmpty() || incomingTombstones.isNotEmpty()) {
            FileApexServices.noteRepository.onPeerBulletinBatchIngested(
                newlyArrived,
                incomingTombstones,
                boardWasEmpty
            )
        }
        if (kindRetractedMessageIds.isNotEmpty()) {
            FileApexServices.noteRepository.onPeerBulletinKindRetracted(kindRetractedMessageIds)
        }
        return BulletinSyncAck(
            packetId = batch.packetId,
            originDeviceId = loadLocalIdentity().deviceId,
            acceptedPayloadIds = accepted.distinct()
        )
    }

    suspend fun processIncomingAck(ack: BulletinSyncAck) {
        transactionDao.applyAck(ack.originDeviceId, ack.acceptedPayloadIds.distinct())
    }

    suspend fun requestFullFile(messageId: String): String? {
        val message = repository.getMessage(messageId) ?: return null
        val meta = repository.decodeFileMetadata(message) ?: return null
        if (!meta.localPath.isNullOrBlank() && kotlinx.io.files.SystemFileSystem.exists(
                kotlinx.io.files.Path(meta.localPath)
            )
        ) {
            return meta.localPath
        }
        val originId = meta.originNode.ifBlank { message.originDeviceId }
        val origin = FileApexServices.deviceRepositoryOrNull()?.getDevice(originId)
            ?: FileApexServices.deviceRepositoryOrNull()?.getDevice(message.originDeviceId)
            ?: return null
        val route = FileApexServices.presenceMonitor.probeNow(origin, RouteUse.Bulk)
        val host = route?.host ?: origin.lastKnownIp
        val port = route?.port ?: origin.port
        if (host.isBlank() || port <= 0) return null
        ServerLifecycleManager.ensureRunning()
        return cancellableCatching {
            FileApexServices.client.downloadBulletinFile(
                host = host,
                port = port,
                messageId = messageId,
                fileName = meta.fileName,
                expectedSha256 = meta.sha256,
                expectedSizeBytes = meta.sizeBytes
            )
        }.getOrNull()?.also { localPath ->
            repository.bindLocalPath(messageId, localPath)
        }
    }

    private fun requestDrain() {
        drainQueued = true
        synchronized(drainScheduleLock) {
            val running = pendingDrainJob
            if (running != null && running.isActive) return
            pendingDrainJob = scope.launch {
                try {
                    do {
                        drainQueued = false
                        delay(DRAIN_DEBOUNCE_MS)
                        drainOutbox()
                    } while (drainQueued)
                } finally {
                    synchronized(drainScheduleLock) {
                        pendingDrainJob = null
                    }
                    if (drainQueued) requestDrain()
                }
            }
        }
    }

    /**
     * Starts one drain per device that has something queued. Nothing here waits on the network: every device is its
     * own job, so a dead or slow device never delays another device or the next post.
     */
    fun drainOutbox() {
        scope.launch(Dispatchers.IO) {
            if (outboxDao.count() == 0) return@launch
            repository.pruneStaleOutbox()
            val selfId = loadLocalIdentity().deviceId
            val devices = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
            for (device in devices) {
                if (device.deviceId == selfId || !BulletinBoardPolicy.takesPartInBulletin(device)) continue
                if (!device.supportsBulletinSync()) continue
                drainRunner.launch(device.deviceId) { drainDevice(device) }
            }
        }
    }

    private suspend fun drainDevice(device: PairedDeviceEntity) {
        if (!peerBackoff.canAttempt(device.deviceId, TimeUtils.now())) return
        val entries = repository.getOutboxForDevice(device.deviceId, BulletinBoardPolicy.SYNC_BATCH_LIMIT)
        if (entries.isEmpty()) return
        val presence = FileApexServices.presenceMonitor
        val route = presence.endpointIfOnline(device)
        val host = route?.host ?: device.lastKnownIp
        val port = route?.port ?: device.port
        if (!BulletinOutboxDrainPolicy.shouldAttemptPeer(
                supportsBulletinSync = device.supportsBulletinSync(),
                host = host,
                port = port,
                isOnline = presence.isDeviceOnline(device)
            )
        ) return
        if (!PeerLanHttpPolicy.canRoute(host)) return
        val batch = buildBatch(entries)
        if (batch.items.isEmpty()) return
        ServerLifecycleManager.ensureRunning()
        drainPeer(PeerDrainJob(device.deviceId, device.deviceName, host, port, entries, batch))
    }

    private suspend fun drainPeer(job: PeerDrainJob) {
        val targetId = job.entries.firstOrNull()?.targetDeviceId
        if (targetId != null && FileApexServices.deviceRepositoryOrNull()?.getDevice(targetId) == null) {
            for (entry in job.entries) {
                repository.removeOutboxEntry(entry.outboxId)
            }
            return
        }
        cancellableCatching {
            val ack = FileApexServices.client.postBulletinSyncBatch(job.host, job.port, job.batch)
            processIncomingAck(ack)
            peerBackoff.onSuccess(job.deviceId)
            for (entry in job.entries) {
                if (ack.acceptedPayloadIds.contains(entry.payloadId)) {
                    repository.removeOutboxEntry(entry.outboxId)
                } else {
                    recordRetry(entry)
                }
            }
        }.onFailure { error ->
            FileApexServices.deviceRepositoryOrNull()?.getDevice(job.deviceId)?.let {
                FileApexServices.presenceMonitor.reportRouteFailure(it, job.host)
            }
            val delayMs = peerBackoff.onFailure(job.deviceId, TimeUtils.now())
            println(
                "BulletinBoardSyncEngine: drain to ${job.deviceName} failed - ${error.message}; " +
                    "next attempt in ${delayMs / 1000}s"
            )
            for (entry in job.entries) {
                recordRetry(entry)
            }
            scope.launch {
                delay(delayMs)
                requestDrain()
            }
        }
    }

    private suspend fun recordRetry(entry: OutboxEntity) {
        if (entry.retryCount + 1 >= MAX_OUTBOX_RETRIES) {
            println(
                "BulletinBoardSyncEngine: dropping outbox ${entry.payloadId} for ${entry.targetDeviceId} " +
                    "after ${entry.retryCount + 1} attempts"
            )
            repository.removeOutboxEntry(entry.outboxId)
        } else {
            outboxDao.incrementRetry(entry.outboxId)
        }
    }

    private suspend fun buildBatch(entries: List<OutboxEntity>): BulletinSyncBatch {
        val self = loadLocalIdentity()
        val items = entries.mapNotNull { entry ->
            when (entry.payloadType) {
                BulletinPayloadType.MESSAGE -> {
                    val message = messageDao.getById(entry.payloadId) ?: return@mapNotNull null
                    BulletinSyncItem(
                        payloadType = BulletinPayloadType.MESSAGE,
                        payloadId = entry.payloadId,
                        body = json.encodeToString(
                            BulletinMessagePayload(
                                id = message.id,
                                originDeviceId = message.originDeviceId,
                                senderName = message.senderName,
                                content = repository.stripRemoteLocalPath(message.content, message.contentType),
                                contentType = message.contentType,
                                timestamp = message.timestamp,
                                isPinned = message.isPinned
                            )
                        )
                    )
                }
                BulletinPayloadType.TOMBSTONE -> {
                    val tombstone = tombstoneDao.getById(entry.payloadId) ?: return@mapNotNull null
                    BulletinSyncItem(
                        payloadType = BulletinPayloadType.TOMBSTONE,
                        payloadId = entry.payloadId,
                        body = json.encodeToString(
                            BulletinTombstonePayload(
                                id = tombstone.id,
                                deletedAt = tombstone.deletedAt,
                                originDeviceId = tombstone.originDeviceId,
                                remotePurge = tombstone.remotePurge
                            )
                        )
                    )
                }
                BulletinPayloadType.RETRACT_BY_KIND -> {
                    val payload = repository.pendingRetractByKind(entry.payloadId)
                        ?: BulletinMessageKind.decodeRetractPayloadId(entry.payloadId)?.let { (originDeviceId, contentType) ->
                            BulletinRetractByKindPayload(
                                originDeviceId = originDeviceId,
                                contentType = contentType,
                                deletedAt = TimeUtils.now()
                            )
                        }
                        ?: return@mapNotNull null
                    BulletinSyncItem(
                        payloadType = BulletinPayloadType.RETRACT_BY_KIND,
                        payloadId = entry.payloadId,
                        body = json.encodeToString(payload)
                    )
                }
                else -> null
            }
        }
        return BulletinSyncBatch(
            packetId = "pkt-" + TimeUtils.now() + "-" + (1000..9999).random(),
            originDeviceId = self.deviceId,
            items = items
        )
    }

    private suspend fun runMaintenance() {
        cancellableCatching {
            repository.pruneStaleOutbox()
            repository.pruneOldMessages()
        }
    }

    companion object {
        private const val DRAIN_DEBOUNCE_MS = 400L
        private const val MAX_OUTBOX_RETRIES = 25
        private const val MAX_DELTA_PAGES = 50
        private const val PULL_RETRY_MS = 60_000L
        private const val MAINTENANCE_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

private data class PeerDrainJob(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val entries: List<OutboxEntity>,
    val batch: BulletinSyncBatch
)
