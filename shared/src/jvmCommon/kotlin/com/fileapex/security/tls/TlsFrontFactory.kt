package com.fileapex.security.tls

import com.fileapex.di.FileApexServices
import com.fileapex.network.LanInterfaceBinding
import com.fileapex.data.device.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket

/** Builds the TLS front for the platform's identity store. */
object TlsFrontFactory {
    private const val PREFERRED_OFFSET = 1

    /** Registers the factory. [storeProvider] may return null before the platform context exists. */
    fun install(storeProvider: () -> TlsIdentityStore?) {
        PeerTlsConnector.configure(storeProvider)
        TlsFrontProvider.factory = { bindHost -> create(storeProvider(), bindHost) }
    }

    private fun create(store: TlsIdentityStore?, bindHost: String): TlsFront? {
        store ?: return null
        val identity = try {
            store.getOrCreate()
        } catch (e: TlsIdentityUnavailableException) {
            println("TlsFront: identity unavailable, TLS off - ${e.message}")
            return null
        }
        LocalTlsInfo.pin = identity.pin
        val repository = FileApexServices.deviceRepository
        val directory = RosterTlsPeerDirectory { runBlocking { repository.listDevices() } }
        directory.refresh()
        val httpPort = FileApexServices.localIdentity.sharePort
        val server = TlsFrontServer(
            identity = { identity },
            directory = directory,
            bindHost = bindHost,
            preferredPort = httpPort + PREFERRED_OFFSET,
            bridgePort = freeLoopbackPort(),
            log = { message, error ->
                if (error != null) println("TlsFront: $message :: ${error.message}") else println("TlsFront: $message")
            }
        )
        return RosterWatchingFront(server, repository, directory)
    }

    private fun freeLoopbackPort(): Int =
        ServerSocket(0, 1, TlsFrontServer.BRIDGE_ADDRESS).use { it.localPort }
}

/** Keeps the pin directory in step with the roster for as long as the front runs. */
private class RosterWatchingFront(
    private val inner: TlsFrontServer,
    private val repository: DeviceRepository,
    private val directory: RosterTlsPeerDirectory
) : TlsFront {
    private var scope: CoroutineScope? = null

    override val bridgePort: Int get() = inner.bridgePort

    override fun start(): Int {
        val port = inner.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            repository.observeDevices().onEach { devices ->
                directory.refresh(devices)
                inner.revalidate()
            }.launchIn(it)
        }
        return port
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        inner.stop()
    }
}
