package com.omninode.di

import com.omninode.data.db.OmniNodeDatabase
import com.omninode.data.device.DeviceRepository
import com.omninode.data.device.LocalDeviceRef
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.data.identity.loadLocalIdentity
import com.omninode.data.settings.AppSettings
import com.omninode.data.settings.createAppSettings
import com.omninode.data.transfer.FileTransferService
import com.omninode.domain.pairing.PairingCoordinator
import com.omninode.domain.presence.PeerPresenceMonitor
import com.omninode.domain.transfer.TransferManager
import com.omninode.network.OmniHttpClientFactory
import com.omninode.network.OmniNodeClient
import com.omninode.util.NetworkUtils
import io.ktor.client.HttpClient

object OmniNodeServices {
    @Volatile
    private var database: OmniNodeDatabase? = null

    @Volatile
    private var deviceRepositoryInstance: DeviceRepository? = null

    val deviceRepository: DeviceRepository
        get() = deviceRepositoryInstance
            ?: error("OmniNodeServices.init(database) must be called first")

    /** Process-wide Ktor client (pairing, transfers, updates, desktop cloud). */
    val httpClient: HttpClient by lazy { OmniHttpClientFactory.create() }

    val transferService: FileTransferService by lazy { FileTransferService(client = client) }

    /** Outbound Multi Copy orchestration — single entry for UI and extension handoff. */
    val transferManager: TransferManager by lazy {
        TransferManager(
            deviceRepository = { deviceRepository },
            client = client,
            transferService = transferService,
            readinessCheck = { isDatabaseReady() },
            identityProvider = { loadLocalIdentity() },
            onlineDeviceIds = { presenceMonitor.onlineDeviceIds.value }
        )
    }

    val client: OmniNodeClient by lazy {
        OmniNodeClient(
            client = httpClient,
            json = OmniHttpClientFactory.defaultJson
        )
    }
    val settings: AppSettings by lazy { createAppSettings() }
    val localIdentity: LocalIdentity
        get() = loadLocalIdentity()

    val pairingCoordinator: PairingCoordinator by lazy {
        PairingCoordinator(
            repository = deviceRepository,
            client = client,
            identityProvider = { loadLocalIdentity() }
        )
    }

    val presenceMonitor: PeerPresenceMonitor by lazy {
        PeerPresenceMonitor(
            repository = deviceRepository,
            client = client,
            pairingCoordinator = pairingCoordinator,
            selfDeviceProvider = {
                NetworkUtils.selfAsPairedDevice(loadLocalIdentity())
            }
        )
    }

    fun init(database: OmniNodeDatabase) {
        val existing = this.database
        if (existing != null) {
            check(existing === database) {
                "OmniNodeServices.init must not replace an active Room database instance"
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
        presenceMonitor.start()
    }

    fun isDatabaseReady(): Boolean = database != null && deviceRepositoryInstance != null

    fun deviceRepositoryOrNull(): DeviceRepository? = deviceRepositoryInstance
}
