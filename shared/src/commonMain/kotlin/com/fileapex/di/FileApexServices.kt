package com.fileapex.di

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.device.recoverEmptyRosterIfNeeded
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
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
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

object FileApexServices {
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bootstrapDeferred = CompletableDeferred<Unit>()

    @Volatile
    private var bootstrapStarted = false

    @Volatile
    private var database: FileApexDatabase? = null

    @Volatile
    private var deviceRepositoryInstance: DeviceRepository? = null

    val deviceRepository: DeviceRepository
        get() = deviceRepositoryInstance
            ?: error("FileApexServices.init(database) must be called first")

    /** Process-wide Ktor client (pairing, transfers, updates, desktop cloud). */
    val httpClient: HttpClient by lazy { FileApexHttpClientFactory.create() }

    val transferService: FileTransferService by lazy { FileTransferService(client = client) }

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

    val pairingCoordinator: PairingCoordinator by lazy {
        PairingCoordinator(
            repository = deviceRepository,
            client = client,
            identityProvider = { loadLocalIdentity() },
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
        this.deviceRepositoryInstance = DeviceRepository(database.deviceDao()) {
            val identity = loadLocalIdentity()
            LocalDeviceRef(
                deviceId = identity.deviceId,
                endpoints = NetworkUtils.shareEndpoints(identity)
            )
        }
        LocalDeviceNameStore.ensureLoaded()
        presenceMonitor.ensureOnlineSnapshotWatcher()
        transferQueue.ensureDrainWatcher()
        bootstrapScope.launch {
            runCatching {
                recoverEmptyRosterIfNeeded(deviceRepository)
            }.onFailure { error ->
                println("FileApexServices: roster recovery skipped — ${error.message}")
            }
        }
        markBootstrapComplete()
    }

    /**
     * Desktop cold start: open Room on a background dispatcher while Compose creates the window.
     * Android continues to call [init] synchronously from [com.fileapex.FileApexApplication].
     */
    fun beginBootstrap(createDatabase: () -> FileApexDatabase) {
        if (isDatabaseReady()) {
            markBootstrapComplete()
            return
        }
        if (bootstrapStarted) return
        bootstrapStarted = true
        bootstrapScope.launch(Dispatchers.IO) {
            runCatching {
                init(createDatabase())
            }.onFailure { error ->
                println("FileApexServices: bootstrap failed — ${error.message}")
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
}
