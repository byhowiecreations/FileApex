package com.fileapex.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Blocking UDP wake listener (no [DatagramSocket.available] polling).
 * Socket close unblocks [DatagramSocket.receive] for clean shutdown.
 */
class UdpWakeReceiver(
    private val onWakeAccepted: suspend () -> Unit,
    private val onSignedWake: (ByteArray, String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    private val processMutex = Mutex()
    private var supervisor = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var listenJob: Job? = null

    @Volatile
    private var socket: DatagramSocket? = null

    fun start() {
        if (listenJob?.isActive == true) return
        if (supervisor.isCancelled) {
            supervisor = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + supervisor)
        }
        listenJob = scope.launch {
            listenLoop()
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        runCatching { socket?.close() }
        socket = null
        supervisor.cancel()
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(WakeHello.MAX_PACKET)
        while (currentCoroutineContext().isActive) {
            val activeSocket = openSocket()
            if (activeSocket == null) {
                delay(SOCKET_RETRY_MS)
                continue
            }
            try {
                while (currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    // Pure blocking receive — yields the thread until a datagram arrives.
                    activeSocket.receive(packet)
                    if (!WakeSourcePolicy.accept(packet.address?.hostAddress.orEmpty(), com.fileapex.util.NetworkUtils.lanBindCandidates())) {
                        continue
                    }
                    val bytes = packet.data.copyOf(packet.length)
                    if (WakeHello.looksSigned(bytes)) {
                        val toUs = WakeHelloReceiver.addressedToThisDevice(bytes)
                        if (toUs) {
                            WakeSignalGate.noteSigned()
                            onSignedWake(bytes, packet.address?.hostAddress.orEmpty())
                        }
                        continue
                    }
                    val payload = String(bytes, Charsets.UTF_8)
                    if (payload != WakeProtocol.PAYLOAD) continue
                    if (!WakeSignalGate.tryAccept()) continue
                    if (!processMutex.tryLock()) continue
                    try {
                        onWakeAccepted()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        onLog("Wake processing failed: ${error.message}")
                    } finally {
                        processMutex.unlock()
                    }
                }
            } catch (_: SocketException) {
                if (!currentCoroutineContext().isActive) return
                onLog("UDP wake socket interrupted; reopening")
            } finally {
                runCatching { activeSocket.close() }
                if (socket === activeSocket) {
                    socket = null
                }
            }
        }
    }

    private fun openSocket(): DatagramSocket? = openWakeListenerSocket(onLog)?.also { socket = it }

    companion object {
        private const val SOCKET_RETRY_MS = 1_000L
    }
}
