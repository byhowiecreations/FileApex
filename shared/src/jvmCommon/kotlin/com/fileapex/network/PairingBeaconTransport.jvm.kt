package com.fileapex.network

import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.util.NetworkUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal expect fun acquirePairingMulticastLock()

internal expect fun releasePairingMulticastLock()

actual object PairingBeaconTransport {
    private val mutex = Mutex()
    private var supervisor = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    @Volatile
    private var currentBeacon: PairingBeacon? = null
    @Volatile
    private var listenSocket: DatagramSocket? = null
    @Volatile
    private var broadcastLockHeld = false
    @Volatile
    private var listenLockHeld = false

    actual fun sendBeaconOnce(beacon: PairingBeacon) {
        val packetBytes = PairingBeacon.encodePacket(
            beacon.copy(timestamp = com.fileapex.util.TimeUtils.now())
        )
        sendOnLanInterfaces(packetBytes)
    }

    actual fun startBroadcast(beacon: PairingBeacon) {
        currentBeacon = beacon
        launchExclusive {
            stopBroadcastLocked()
            currentBeacon = beacon
            ensureScope()
            acquireBroadcastLock()
            sendBeaconOnce(beacon)
            broadcastJob = scope.launch { broadcastLoop() }
            log(
                "broadcast started code=${beacon.pairingCode} " +
                    "host=${beacon.ipAddress}:${beacon.port} " +
                    "via ${PairingBeacon.MULTICAST_ADDRESS}:${PairingBeacon.PORT}"
            )
        }
    }

    actual fun stopBroadcast() {
        launchExclusive { stopBroadcastLocked() }
    }

    actual fun startListener(onBeacon: (PairingBeacon) -> Unit) {
        launchExclusive {
            stopListenerLocked()
            ensureScope()
            acquireListenLock()
            listenJob = scope.launch { listenLoop(onBeacon) }
            log("listener start requested on ${PairingBeacon.MULTICAST_ADDRESS}:${PairingBeacon.PORT}")
        }
    }

    actual fun stopListener() {
        launchExclusive { stopListenerLocked() }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        ensureScope()
        scope.launch {
            mutex.withLock { block() }
        }
    }

    private fun ensureScope() {
        if (supervisor.isCancelled) {
            supervisor = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + supervisor)
        }
    }

    private fun stopBroadcastLocked() {
        broadcastJob?.cancel()
        broadcastJob = null
        currentBeacon = null
        if (broadcastLockHeld) {
            releasePairingMulticastLock()
            broadcastLockHeld = false
        }
    }

    private fun stopListenerLocked() {
        listenJob?.cancel()
        listenJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
        if (listenLockHeld) {
            releasePairingMulticastLock()
            listenLockHeld = false
        }
    }

    private fun acquireBroadcastLock() {
        if (!broadcastLockHeld) {
            acquirePairingMulticastLock()
            broadcastLockHeld = true
        }
    }

    private fun acquireListenLock() {
        if (!listenLockHeld) {
            acquirePairingMulticastLock()
            listenLockHeld = true
        }
    }

    private suspend fun broadcastLoop() {
        while (currentCoroutineContext().isActive) {
            val beacon = currentBeacon ?: break
            sendBeaconOnce(beacon)
            delay(PairingBeacon.BROADCAST_INTERVAL_MS)
        }
    }

    private suspend fun listenLoop(onBeacon: (PairingBeacon) -> Unit) {
        val buffer = ByteArray(2048)
        while (currentCoroutineContext().isActive) {
            val socket = openListenerSocket()
            if (socket == null) {
                log("listener bind failed — retrying in 1s")
                delay(1_000L)
                continue
            }
            try {
                while (currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val beacon = PairingBeacon.parsePacket(raw) ?: continue
                    log(
                        "beacon from ${beacon.deviceName} @ ${beacon.ipAddress}:${beacon.port} " +
                            "code=${beacon.pairingCode}"
                    )
                    onBeacon(beacon)
                }
            } catch (_: SocketException) {
                if (!currentCoroutineContext().isActive) return
            } finally {
                runCatching { socket.close() }
                if (listenSocket === socket) {
                    listenSocket = null
                }
            }
        }
    }

    /**
     * Wildcard bind first. Binding only to the unicast LAN IP (previous behavior) drops
     * 255.255.255.255 / subnet-broadcast packets on several Android OEMs (Honor Magic V5).
     */
    private fun openListenerSocket(): DatagramSocket? {
        val wildcard = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PairingBeacon.PORT))
                joinAllPairingGroups()
                log(
                    "listener bound 0.0.0.0:${PairingBeacon.PORT} " +
                        "multicast ${PairingBeacon.MULTICAST_ADDRESS}"
                )
            }
        }.onFailure { error ->
            log("listener wildcard bind failed: ${error.message}")
        }.getOrNull()
        if (wildcard != null) {
            listenSocket = wildcard
            return wildcard
        }
        return openPairingListenerOnPrimaryInterface()
    }

    private fun openPairingListenerOnPrimaryInterface(): DatagramSocket? {
        val localIp = LanInterfaceBinding.lanBindCandidates().firstOrNull()
        val networkInterface = localIp?.let { networkInterfaceForIp(it) }
        if (localIp == null || networkInterface == null) {
            log("listener primary bind skipped — no LAN interface")
            return null
        }
        return runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(localIp, PairingBeacon.PORT))
                joinPairingGroup(networkInterface)
                log(
                    "listener bound $localIp:${PairingBeacon.PORT} " +
                        "(fallback) multicast ${PairingBeacon.MULTICAST_ADDRESS}"
                )
            }
        }.onFailure { error ->
            log("listener primary bind failed on $localIp: ${error.message}")
        }.getOrNull().also { listenSocket = it }
    }

    private fun MulticastSocket.joinPairingGroup(networkInterface: NetworkInterface) {
        val groupAddress = InetAddress.getByName(PairingBeacon.MULTICAST_ADDRESS)
        joinGroup(InetSocketAddress(groupAddress, PairingBeacon.PORT), networkInterface)
    }

    private fun MulticastSocket.joinAllPairingGroups() {
        val groupAddress = InetAddress.getByName(PairingBeacon.MULTICAST_ADDRESS)
        var joinedAny = false
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .forEach { networkInterface ->
                val hasIpv4 = networkInterface.inetAddresses.toList().any { it is Inet4Address }
                if (!hasIpv4) return@forEach
                runCatching {
                    joinGroup(
                        InetSocketAddress(groupAddress, PairingBeacon.PORT),
                        networkInterface
                    )
                    joinedAny = true
                }.onFailure { error ->
                    log("multicast join skipped on ${networkInterface.displayName}: ${error.message}")
                }
            }
        if (!joinedAny) {
            log("multicast join failed: no active IPv4 network interface")
        }
    }

    private fun sendOnLanInterfaces(payload: ByteArray) {
        val candidates = NetworkUtils.lanBindCandidates()
        var sent = 0
        for (localIp in candidates) {
            val networkInterface = networkInterfaceForIp(localIp) ?: continue
            sent += sendViaBroadcastSocket(localIp, networkInterface, payload)
        }
        // Unbound send helps when macOS Local Network TCC drops interface-bound UDP.
        sent += sendViaUnboundBroadcast(payload)
        if (sent == 0) {
            log("beacon send failed on all interfaces (candidates=${candidates.size})")
        }
    }

    private fun sendViaBroadcastSocket(
        localIp: String,
        networkInterface: NetworkInterface,
        payload: ByteArray
    ): Int {
        var sent = 0
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(localIp, 0))
            val targets = linkedSetOf(
                WakeProtocol.BROADCAST_ADDRESS,
                PairingBeacon.MULTICAST_ADDRESS
            )
            directedBroadcastOrNull(localIp, networkInterface)?.let { targets.add(it) }
            for (target in targets) {
                runCatching {
                    val address = InetAddress.getByName(target)
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        address,
                        PairingBeacon.PORT
                    )
                    socket.send(packet)
                    sent++
                }
            }
        }
        if (sent > 0) {
            log("beacon sent from $localIp ($sent targets)")
        }
        return sent
    }

    private fun sendViaUnboundBroadcast(payload: ByteArray): Int {
        var sent = 0
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val targets = linkedSetOf(
                    WakeProtocol.BROADCAST_ADDRESS,
                    PairingBeacon.MULTICAST_ADDRESS
                )
                for (localIp in NetworkUtils.lanBindCandidates()) {
                    val networkInterface = networkInterfaceForIp(localIp) ?: continue
                    directedBroadcastOrNull(localIp, networkInterface)?.let { targets.add(it) }
                }
                for (target in targets) {
                    runCatching {
                        val address = InetAddress.getByName(target)
                        val packet = DatagramPacket(
                            payload,
                            payload.size,
                            address,
                            PairingBeacon.PORT
                        )
                        socket.send(packet)
                        sent++
                    }
                }
            }
        }.onFailure { error ->
            log("unbound beacon send failed: ${error.message}")
        }
        if (sent > 0) {
            log("beacon sent unbound ($sent targets)")
        }
        return sent
    }

    private fun networkInterfaceForIp(localIp: String): NetworkInterface? =
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull { iface ->
            iface.isUp &&
                !iface.isLoopback &&
                iface.inetAddresses.toList().any { address ->
                    address is Inet4Address && address.hostAddress == localIp
                }
        }

    private fun directedBroadcastOrNull(localIp: String, networkInterface: NetworkInterface): String? {
        val localAddress = InetAddress.getByName(localIp) as? Inet4Address ?: return null
        val ifaceAddress = networkInterface.interfaceAddresses.firstOrNull { address ->
            address.address is Inet4Address && address.address.hostAddress == localIp
        } ?: return null
        val prefixLength = ifaceAddress.networkPrefixLength.toInt()
        if (prefixLength !in 1..32) return null
        val ip = localAddress.address
        val mask = ByteArray(4)
        for (i in 0 until 4) {
            val bits = (prefixLength - i * 8).coerceIn(0, 8)
            mask[i] = ((0xFF shl (8 - bits)) and 0xFF).toByte()
        }
        val broadcast = ByteArray(4) { index ->
            (ip[index].toInt() and mask[index].toInt() or (mask[index].toInt().inv() and 0xFF)).toByte()
        }
        return InetAddress.getByAddress(broadcast).hostAddress
    }

    private fun log(message: String) {
        pairingBeaconLog(message)
    }
}
