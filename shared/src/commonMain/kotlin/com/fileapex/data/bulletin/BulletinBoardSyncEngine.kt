package com.fileapex.data.bulletin

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.textContainsWebUrl
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val drainMutex = Mutex()
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

    fun ensureStarted() {
        if (drainWatcherStarted) return
        drainWatcherStarted = true
        scope.launch {
            FileApexServices.presenceMonitor.reachabilityEpochMs.collect { requestDrain() }
        }
        scope.launch {
            FileApexServices.presenceMonitor.onlineSnapshotEpochMs.collect { requestDrain() }
        }
        maintenanceJob = scope.launch {
            while (true) {
                delay(MAINTENANCE_INTERVAL_MS)
                runMaintenance()
            }
        }
    }

    suspend fun publishMessage(message: MessageEntity) {
        val peers = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
        val selfId = loadLocalIdentity().deviceId
        val remotePeers = peers.filter { it.deviceId != selfId }
        val bulletinPeers = remotePeers.filter { it.supportsBulletinSync() }
        val legacyPeers = remotePeers.filterNot { it.supportsBulletinSync() }

        if (bulletinPeers.isNotEmpty()) {
            repository.enqueueOutboxForAllPeers(
                payloadType = BulletinPayloadType.MESSAGE,
                payloadId = message.id,
                peerIds = bulletinPeers.map { it.deviceId }
            )
            requestDrain()
        }
        BulletinLegacyRelay.dispatchMessage(message, legacyPeers)
    }

    suspend fun publishTombstone(messageId: String) {
        val peers = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
        val selfId = loadLocalIdentity().deviceId
        val remotePeers = peers.filter { it.deviceId != selfId }
        val bulletinPeers = remotePeers.filter { it.supportsBulletinSync() }
        val legacyPeers = remotePeers.filterNot { it.supportsBulletinSync() }

        if (bulletinPeers.isNotEmpty()) {
            repository.enqueueOutboxForAllPeers(
                payloadType = BulletinPayloadType.TOMBSTONE,
                payloadId = messageId,
                peerIds = bulletinPeers.map { it.deviceId }
            )
            requestDrain()
        }
        FcmWakeCoordinator.dispatchPresenceWakeToLinkedPeers()
        val snapshot = repository.getMessage(messageId)?.toNoteRecord()
        BulletinLegacyRelay.dispatchTombstone(messageId, legacyPeers, snapshot)
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
        for (item in batch.items) {
            if (tombstoneDao.countById(item.payloadId) > 0 && item.payloadType == BulletinPayloadType.MESSAGE) {
                continue
            }
            when (item.payloadType) {
                BulletinPayloadType.MESSAGE -> {
                    val payload = json.decodeFromString<BulletinMessagePayload>(item.body)
                    if (tombstoneDao.countById(payload.id) > 0) continue
                    incomingMessages += MessageEntity(
                        id = payload.id,
                        originDeviceId = payload.originDeviceId,
                        senderName = payload.senderName,
                        content = repository.stripMissingLocalPath(
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
            }
        }
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
            BulletinRemoteFilePurgeHandler.handle(messageId)
        }
        if (newlyArrived.isNotEmpty() || incomingTombstones.isNotEmpty()) {
            FileApexServices.noteRepository.onPeerBulletinBatchIngested(newlyArrived, incomingTombstones)
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
        val host = origin.lastKnownIp
        val port = origin.port
        if (host.isBlank() || port <= 0) return null
        ServerLifecycleManager.ensureRunning()
        return runCatching {
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

    suspend fun drainOutbox() {
        drainMutex.withLock {
            repository.pruneStaleOutbox()
            val selfId = loadLocalIdentity().deviceId
            val devices = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
            val presence = FileApexServices.presenceMonitor
            val pending = mutableListOf<PeerDrainJob>()
            for (device in devices) {
                if (device.deviceId == selfId) continue
                val host = device.lastKnownIp
                val port = device.port
                if (!BulletinOutboxDrainPolicy.shouldAttemptPeer(
                        supportsBulletinSync = device.supportsBulletinSync(),
                        host = host,
                        port = port,
                        isOnline = presence.isDeviceOnline(device)
                    )
                ) {
                    continue
                }
                if (!PeerLanHttpPolicy.canRoute(host)) continue
                val entries = repository.getOutboxForDevice(
                    device.deviceId,
                    BulletinBoardPolicy.SYNC_BATCH_LIMIT
                )
                if (entries.isEmpty()) continue
                val batch = buildBatch(entries)
                if (batch.items.isEmpty()) continue
                pending += PeerDrainJob(device.deviceName, host, port, entries, batch)
            }
            if (pending.isEmpty()) return@withLock
            ServerLifecycleManager.ensureRunning()
            coroutineScope {
                for (job in pending) {
                    launch(Dispatchers.IO) {
                        drainPeer(job)
                    }
                }
            }
        }
    }

    private suspend fun drainPeer(job: PeerDrainJob) {
        runCatching {
            val ack = FileApexServices.client.postBulletinSyncBatch(job.host, job.port, job.batch)
            processIncomingAck(ack)
            for (entry in job.entries) {
                if (ack.acceptedPayloadIds.contains(entry.payloadId)) {
                    repository.removeOutboxEntry(entry.outboxId)
                } else {
                    outboxDao.incrementRetry(entry.outboxId)
                }
            }
        }.onFailure { error ->
            println("BulletinBoardSyncEngine: drain to ${job.deviceName} failed - ${error.message}")
            for (entry in job.entries) {
                outboxDao.incrementRetry(entry.outboxId)
            }
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
                                content = message.content,
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
        runCatching {
            repository.pruneStaleOutbox()
            repository.pruneOldMessages()
        }
    }

    companion object {
        private const val DRAIN_DEBOUNCE_MS = 400L
        private const val MAINTENANCE_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

private data class PeerDrainJob(
    val deviceName: String,
    val host: String,
    val port: Int,
    val entries: List<OutboxEntity>,
    val batch: BulletinSyncBatch
)
