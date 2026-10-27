package com.fileapex.di

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.bulletin.BulletinBoardMerge
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

    @Volatile
    private var bootstrapStarted = false

    @Volatile
    private var database: FileApexDatabase? = null

    @Volatile
    private var bulletinInitialized = false
    private var legacyBulletinPath: String? = null

    @Volatile
    private var bulletinBoardRepositoryInstance: BulletinBoardRepository? = null

    @Volatile
    private var bulletinSyncEngineInstance: BulletinBoardSyncEngine? = null

    @Volatile
    private var deviceRepositoryInstance: DeviceRepository? = null

    @Volatile
    var isPlayStoreBuild: Boolean = false

    /** F-Droid flavor: no Firebase, FCM, Google sign-in or Drive, and F-Droid delivers updates. */
    @Volatile
    var isFdroidBuild: Boolean = false

    /** The Play build is updated by Play and the F-Droid build by F-Droid. */
    val managedByStore: Boolean
        get() = isPlayStoreBuild || isFdroidBuild

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

    private val bulletinUnreadHolder = kotlinx.coroutines.flow.MutableStateFlow<com.fileapex.data.bulletin.BulletinUnreadTracker?>(null)
    val bulletinUnread: kotlinx.coroutines.flow.StateFlow<com.fileapex.data.bulletin.BulletinUnreadTracker?> = bulletinUnreadHolder

    /** Outbound Multi Copy orchestration, single entry for UI and extension handoff. */
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

    private val lanScanBackoff: com.fileapex.domain.presence.LanScanBackoff by lazy {
        com.fileapex.domain.presence.LanScanBackoff(
            com.fileapex.domain.presence.LanPresenceTiming.LAN_SCAN_BACKOFF_BASE_MS,
            com.fileapex.domain.presence.LanPresenceTiming.LAN_SCAN_BACKOFF_MAX_MS,
            com.fileapex.util.TimeUtils::now,
            settings.lanScanBackoffState,
            { settings.lanScanBackoffState = it }
        )
    }

    val peerAdmission: com.fileapex.domain.pairing.PeerAdmission by lazy {
        com.fileapex.domain.pairing.PeerAdmission(
            repository = deviceRepository,
            client = client,
            identityProvider = { loadLocalIdentity() },
            scanBackoff = lanScanBackoff,
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
            client = client,
            lanScanBackoff = lanScanBackoff
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
        client.onRevocationDetected = { host, revokedAt ->
            com.fileapex.session.DeviceSessionManager.clearAllSessions()
            presenceMonitor.refreshOnlineSnapshot()
            RevokedByPeerNotice.onRevoked(host, deviceRepository.listDevices())
            com.fileapex.domain.pairing.KickedByPeer.onRevoked(host, revokedAt, deviceRepository.listDevices())
        }
        client.membershipVersionProvider = { deviceRepository.selfMembershipVersion() }
        // Routes must exist before the first request, or a pinned peer could be reached over HTTP.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            deviceRepository.listDevices().also { com.fileapex.security.tls.PeerTlsRoutes.update(it); com.fileapex.domain.clipboard.PeerE2eeVersions.update(it) }
        }
        bootstrapScope.launch {
            deviceRepository.observeDevices().collect { com.fileapex.security.tls.PeerTlsRoutes.update(it); com.fileapex.domain.clipboard.PeerE2eeVersions.update(it) }
        }
        com.fileapex.security.tls.TlsPinAnnouncer(
            repository = deviceRepository,
            client = client,
            onlineDeviceIds = { presenceMonitor.onlineDeviceIds.value },
            scope = bootstrapScope
        ).ensureStarted()
        com.fileapex.cloud.seal.CloudRecordResealer.start(bootstrapScope)
        bootstrapScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(ROSTER_CATCH_UP_FIRST_MS)
            while (true) {
                cancellableCatching { pairingCoordinator.catchUpFromPeers(presenceMonitor.onlineDeviceIds.value) }
                    .onFailure { error -> if (error is kotlinx.coroutines.CancellationException) throw error }
                kotlinx.coroutines.delay(ROSTER_CATCH_UP_EVERY_MS)
            }
        }
        LocalDeviceNameStore.ensureLoaded()
        DeviceNamePeerLabelsStore.ensureLoaded()
        presenceMonitor.ensureOnlineSnapshotWatcher()
        presenceMonitor.ensureLanPollLoop()
        transferQueue.ensureDrainWatcher()
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.ensureStarted()
        bootstrapScope.launch(Dispatchers.IO) {
            runCatching { com.fileapex.tailscale.TailscaleNodeRuntime.attach() }
        }
        com.fileapex.iroh.IrohRuntime.attach()
        com.fileapex.friends.FriendsHub.attach(database)
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

    /**
     * Starts the Bulletin Board on the merged database. [legacyBulletinPath] is the old
     * `bulletin_board.db`; its rows are copied in once (see [BulletinBoardMerge]).
     */
    fun initBulletinBoard(legacyBulletinPath: String) {
        val database = checkNotNull(this.database) { "FileApexServices.init must run before initBulletinBoard" }
        if (bulletinInitialized) return
        bulletinInitialized = true
        this.legacyBulletinPath = legacyBulletinPath
        val repository = BulletinBoardRepository(database)
        bulletinBoardRepositoryInstance = repository
        val syncEngine = BulletinBoardSyncEngine(database, repository, bootstrapScope)
        bulletinSyncEngineInstance = syncEngine
        noteRepository.attachBulletinBoard(repository, syncEngine, bootstrapScope)
        bulletinUnreadHolder.value = com.fileapex.data.bulletin.BulletinUnreadTracker(settings, repository, bootstrapScope)
        bootstrapScope.launch(Dispatchers.IO) {
            cancellableCatching {
                when (val result = BulletinBoardMerge.run(legacyBulletinPath, database)) {
                    BulletinBoardMerge.Result.NothingToMerge -> Unit
                    is BulletinBoardMerge.Result.Merged -> println("FileApexServices: bulletin merge copied $result")
                    is BulletinBoardMerge.Result.Mismatch ->
                        println("FileApexServices: bulletin merge stopped, old file kept - $result")
                }
            }.onFailure { error ->
                println("FileApexServices: bulletin merge skipped - ${error.message}")
            }
            // The engine starts only after the copy so it never syncs from a half-filled table.
            syncEngine.ensureStarted()
        }
    }

    /**
     * Desktop cold start: open Room on a background dispatcher while Compose creates the window.
     * Android continues to call [init] synchronously from [com.fileapex.FileApexApplication].
     */
    fun beginBootstrap(
        createDatabase: () -> FileApexDatabase,
        legacyBulletinPath: String
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
                initBulletinBoard(legacyBulletinPath)
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
            cancellableCatching { db.pendingTransferDao().deleteAll() }
            cancellableCatching { db.messageDao().deleteAllMessages() }
            cancellableCatching { db.tombstoneDao().deleteAllTombstones() }
            cancellableCatching { db.messageFlagDao().deleteAllFlags() }
            cancellableCatching { db.checklistItemDao().deleteAllItems() }
            cancellableCatching { db.outboxDao().deleteAllOutbox() }
            cancellableCatching { db.processedPacketDao().deleteAllPackets() }
            runCatching { db.clearAllTables() }
        }
        // A wipe must not leave the old Bulletin file (or its merge backup) behind to be copied in again.
        legacyBulletinPath?.let { BulletinBoardMerge.deleteLegacyFiles(it) }
    }
}
