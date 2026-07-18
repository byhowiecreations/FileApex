package com.omninode.di

import com.omninode.data.db.OmniNodeDatabase
import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.device.DeviceRepository
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.data.identity.loadLocalIdentity
import com.omninode.data.settings.AppSettings
import com.omninode.data.settings.createAppSettings
import com.omninode.data.transfer.FileTransferService
import com.omninode.domain.pairing.PairingCoordinator
import com.omninode.domain.presence.PeerPresenceMonitor
import com.omninode.network.OmniNodeClient
import com.omninode.platform.localIpv4Addresses

object OmniNodeServices {
    private var database: OmniNodeDatabase? = null

    val deviceRepository: DeviceRepository
        get() = DeviceRepository(requireDb().deviceDao())

    val transferService: FileTransferService by lazy { FileTransferService(client = client) }
    val client: OmniNodeClient by lazy { OmniNodeClient() }
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
                val identity = loadLocalIdentity()
                PairedDeviceEntity(
                    deviceId = identity.deviceId,
                    deviceName = identity.deviceName,
                    lastKnownIp = localIpv4Addresses().firstOrNull() ?: "127.0.0.1",
                    port = identity.sharePort,
                    publicKeyHash = "",
                    rootPath = identity.rootPath
                )
            }
        )
    }

    fun init(database: OmniNodeDatabase) {
        this.database = database
        LocalDeviceNameStore.ensureLoaded()
        presenceMonitor.start()
    }

    private fun requireDb(): OmniNodeDatabase {
        return database ?: error("OmniNodeServices.init(database) must be called first")
    }
}
