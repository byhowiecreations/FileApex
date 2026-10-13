package com.fileapex.di

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.bulletin.BulletinBoardDatabase
import com.fileapex.data.bulletin.BulletinBoardRepository
import com.fileapex.data.bulletin.BulletinBoardSyncEngine
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.device.SettingsMembershipStore
import com.fileapex.data.device.recoverEmptyRosterIfNeeded
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.DeviceNamePeerLabelsStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.note.NoteRepository
import com.fileapex.data.settings.AppSettings
import com.fileapex.data.settings.createAppSettings
import com.fileapex.data.transfer.FileTransferService
import com.fileapex.domain.pairing.PairingCoordinator
import com.fileapex.domain.presence.PeerPresenceMonitor
import com.fileapex.domain.transfer.TransferManager
import com.fileapex.domain.transfer.TransferQueueCoordinator
import com.fileapex.network.FileApexHttpClientFactory
import com.fileapex.network.FileApexClient
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

object FileApexServices {
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bootstrapDeferred = CompletableDeferred<Unit>()
    private const val ROSTER_CATCH_UP_FIRST_MS = 60_000L
    private const val ROSTER_CATCH_UP_EVERY_MS = 10L * 60_000L
    private const val ROSTER_CATCH_UP_SMALL_MS = 60_000L
    private const val ROSTER_SMALL_COUNT = 3

    @Volatile
    private var bootstrapStarted = false

    @Volatile
    private var database: FileApexDatabase? = null

    @Volatile
    private var bulletinDatabase: BulletinBoardDatabase? = null

    @Volatile
    private var bulletinBoardRepositoryInstance: BulletinBoardRepository? = null

    @Volatile
    private var bulletinSyncEngineInstance: BulletinBoardSyncEngine? = null

    @Volatile
    private var deviceRepositoryInstance: DeviceRepository? = null

    @Volatile
    var isPlayStoreBuild: Boolean = false

    @Volatile
    var buildChannel: String = "GitHub"

    val deviceRepository: DeviceRepository
        get() = deviceRepositoryInstance
            ?: error("FileApexServices.init(database) must be called first")

    /** Process-wide Ktor client (pairing, transfers, updates, desktop cloud). */
    val httpClient: HttpClient by lazy { FileApexHttpClientFactory.create() }

    val transferService: FileTransferService by lazy { FileTransferService(client = client) }

    val noteRepository: NoteRepository by lazy { NoteRepository() }

    val bulletinBoardRepository: BulletinBoardRepository
        get() = bulletinBoardRepositoryInstance
            ?: error("FileApexServices.initBulletinBoard(database) must be called first")

    val bulletinSyncEngine: BulletinBoardSyncEngine
        get() = bulletinSyncEngineInstance
            ?: error("FileApexServices.initBulletinBoard(database) must be called first")

    fun bulletinSyncEngineOrNull(): BulletinBoardSyncEngine? = bulletinSyncEngineInstance

    /** Outbound Multi Copy orchestration — single entry for UI and extension handoff. */
    val transferManager: TransferManager by lazy {
        TransferManager(
            deviceRepository = { deviceRepository },
            client = client,
            transferService = transferService,
            readinessCheck = { isDatabaseReady() },
            identityProvider = { loadLocalIdentity() },
            onlineDeviceIds = { presenceMonitor.onlineDeviceIds.value },
            presenceMonitor = { presenceMonitor }
        )
    }

    val transferQueue: TransferQueueCoordinator by lazy {
        TransferQueueCoordinator(
            dao = database!!.pendingTransferDao(),
            deviceRepository = deviceRepository,
            transferManager = transferManager,
            presenceMonitor = presenceMonitor,
            scope = bootstrapScope
        )
    }

    val client: FileApexClient by lazy {
        FileApexClient(json = FileApexHttpClientFactory.defaultJson)
    }
    val settings: AppSettings by lazy { createAppSettings() }
    val localIdentity: LocalIdentity
        get() = loadLocalIdentity()

    val peerAdmission: com.fileapex.domain.pairing.PeerAdmission by lazy {
        com.fileapex.domain.pairing.PeerAdmission(
            repository = deviceRepository,
            client = client,
            identityProvider = { loadLocalIdentity() },
            onSuperseded = { removed -> pairingCoordinator.broadcastDeviceRemoval(removed) },
            onPromoted = { device ->
                presenceMonitor.notifyPassiveReachability(device.deviceId, epochMs = com.fileapex.util.TimeUtils.now())
            }
        )
    }

    val pairingCoordinator: PairingCoordinator by lazy {
        PairingCoordinator(
            repository = deviceRepository,
            client = client,
            identityProvider = { loadLocalIdentity() },
            admission = { peerAdmission },
            onPassiveReachability = { deviceIds, epochMs ->
                presenceMonitor.notifyPassiveReachability(*deviceIds.toTypedArray(), epochMs = epochMs)
            }
        )
    }

    val presenceMonitor: PeerPresenceMonitor by lazy {
        PeerPresenceMonitor(
            repository = deviceRepository,
            client = client
        )
    }

    fun init(database: FileApexDatabase) {
        val existing = this.database
        if (existing != null) {
            check(existing === database) {
                "FileApexServices.init must not replace an active Room database instance"
            }
            return
        }
        this.database = database
        this.deviceRepositoryInstance = DeviceRepository(
            deviceDao = database.deviceDao(),
            localDeviceProvider = {
                val identity = loadLocalIdentity()
                LocalDeviceRef(
                    deviceId = identity.deviceId,
                    endpoints = NetworkUtils.shareEndpoints(identity)
                )
            },
            membershipStore = SettingsMembershipStore { settings },
            candidates = com.fileapex.data.db.RoomPeerCandidateStore(database.peerCandidateDao())
        )
        client.onRevocationDetected = { host ->
            com.fileapex.session.DeviceSessionManager.clearAllSessions()
            presenceMonitor.refreshOnlineSnapshot()
            RevokedByPeerNotice.onRevoked(host, deviceRepository.listDevices())
        }
        client.membershipVersionProvider = { deviceRepository.selfMembershipVersion() }
        // Routes must exist before the first request, or a pinned peer could be reached over HTTP.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            com.fileapex.security.tls.PeerTlsRoutes.update(deviceRepository.listDevices())
        }
        bootstrapScope.launch {
            deviceRepository.observeDevices().collect { com.fileapex.security.tls.PeerTlsRoutes.update(it) }
        }
        com.fileapex.security.tls.TlsPinAnnouncer(
            repository = deviceRepository,
            client = client,
            onlineDeviceIds = { presenceMonitor.onlineDeviceIds.value },
            scope = bootstrapScope
        ).ensureStarted()
        bootstrapScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(ROSTER_CATCH_UP_FIRST_MS)
            while (true) {
                cancellableCatching { pairingCoordinator.catchUpFromPeers(presenceMonitor.onlineDeviceIds.value) }
                    .onFailure { error -> if (error is kotlinx.coroutines.CancellationException) throw error }
                val small = runCatching { deviceRepository.listDevices().size < ROSTER_SMALL_COUNT }.getOrDefault(false)
                kotlinx.coroutines.delay(if (small) ROSTER_CATCH_UP_SMALL_MS else ROSTER_CATCH_UP_EVERY_MS)
            }
        }
        LocalDeviceNameStore.ensureLoaded()
        DeviceNamePeerLabelsStore.ensureLoaded()
        noteRepository.attachLegacyDao(database.noteDao(), bootstrapScope)
        presenceMonitor.ensureOnlineSnapshotWatcher()
        presenceMonitor.ensureLanPollLoop()
        presenceMonitor.scheduleColdLaunchProbeOnce()
        transferQueue.ensureDrainWatcher()
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.ensureStarted()
        bootstrapScope.launch(Dispatchers.IO) {
            runCatching { com.fileapex.tailscale.TailscaleNodeRuntime.attach() }
        }
        bootstrapScope.launch {
            cancellableCatching { deviceRepository.demoteUnverifiedRows() }
                .onFailure { error -> println("FileApexServices: unverified roster check skipped - ${error.message}") }
            cancellableCatching {
                recoverEmptyRosterIfNeeded(deviceRepository)
            }.onFailure { error ->
                println("FileApexServices: roster recovery skipped - ${error.message}")
            }
            cancellableCatching { peerAdmission.verifyPending() }
                .onFailure { error -> println("FileApexServices: candidate check skipped - ${error.message}") }
        }
        markBootstrapComplete()
    }

    fun initBulletinBoard(database: BulletinBoardDatabase) {
        val existing = bulletinDatabase
        if (existing != null) {
            check(existing === database) {
                "FileApexServices.initBulletinBoard must not replace an active bulletin database"
            }
            return
        }
        bulletinDatabase = database
        val repository = BulletinBoardRepository(database)
        bulletinBoardRepositoryInstance = repository
        val syncEngine = BulletinBoardSyncEngine(database, repository, bootstrapScope)
        bulletinSyncEngineInstance = syncEngine
        syncEngine.ensureStarted()
        noteRepository.attachBulletinBoard(repository, syncEngine, bootstrapScope)
        bootstrapScope.launch {
            cancellableCatching {
                val coreDb = this@FileApexServices.database
                if (coreDb != null) {
                    repository.migrateFromLegacyNotes(coreDb.noteDao())
                }
            }.onFailure { error ->
                println("FileApexServices: bulletin migration skipped - ${error.message}")
            }
        }
    }

    /**
     * Desktop cold start: open Room on a background dispatcher while Compose creates the window.
     * Android continues to call [init] synchronously from [com.fileapex.FileApexApplication].
     */
    fun beginBootstrap(
        createDatabase: () -> FileApexDatabase,
        createBulletinBoard: () -> BulletinBoardDatabase
    ) {
        if (isDatabaseReady()) {
            markBootstrapComplete()
            return
        }
        if (bootstrapStarted) return
        bootstrapStarted = true
        bootstrapScope.launch(Dispatchers.IO) {
            runCatching {
                init(createDatabase())
                initBulletinBoard(createBulletinBoard())
            }.onFailure { error ->
                println("FileApexServices: bootstrap failed - ${error.message}")
                markBootstrapComplete()
            }
        }
    }

    suspend fun awaitBootstrap() {
        bootstrapDeferred.await()
    }

    val isBootstrapComplete: Boolean
        get() = bootstrapDeferred.isCompleted

    private fun markBootstrapComplete() {
        if (!bootstrapDeferred.isCompleted) {
            bootstrapDeferred.complete(Unit)
        }
    }

    fun isDatabaseReady(): Boolean = database != null && deviceRepositoryInstance != null

    fun deviceRepositoryOrNull(): DeviceRepository? = deviceRepositoryInstance

    suspend fun purgeDatabases() {
        database?.let { db ->
            cancellableCatching { db.deviceDao().deleteAllDevices() }
            cancellableCatching { db.deviceDao().deleteAllRemovedDevices() }
            cancellableCatching { db.controlDeliveryDao().deleteAll() }
            cancellableCatching { db.pendingTransferDao().deleteAll() }
            cancellableCatching { db.noteDao().deleteAllNotes() }
            runCatching { db.clearAllTables() }
        }
        bulletinDatabase?.let { bDb ->
            cancellableCatching { bDb.messageDao().deleteAllMessages() }
            cancellableCatching { bDb.tombstoneDao().deleteAllTombstones() }
            cancellableCatching { bDb.outboxDao().deleteAllOutbox() }
            cancellableCatching { bDb.processedPacketDao().deleteAllPackets() }
            runCatching { bDb.clearAllTables() }
        }
    }
}
