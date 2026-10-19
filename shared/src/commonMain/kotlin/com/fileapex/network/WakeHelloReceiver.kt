package com.fileapex.network

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles signed wake packets. A packet only proves the sender holds the pair key; the address it came from
 * is accepted after the presence monitor fetches the sender's identity there, and a hello is answered with a signed ack.
 */
object WakeHelloReceiver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val helloGate = WakeHelloGate(TimeUtils::now)
    private val ackGate = WakeHelloGate(TimeUtils::now)

    /**
     * Cheap and synchronous, for the receive loop: true when the packet is addressed to this device, so the plain wake
     * that follows it in the same burst can be coalesced.
     */
    fun addressedToThisDevice(bytes: ByteArray): Boolean {
        val parsed = WakeHello.parse(bytes) ?: return false
        return WakeHello.addressedTo(parsed, loadLocalIdentity().deviceId)
    }

    /** Verifies and acts off the receive thread. [onVerified] runs first, for example to start the share server. */
    fun handle(bytes: ByteArray, sourceHost: String, onVerified: suspend () -> Unit = {}) {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            try {
                verifyAndAct(bytes, sourceHost, onVerified)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                println("WakeHelloReceiver: handling failed - ${error.message}")
            }
        }
    }

    private suspend fun verifyAndAct(bytes: ByteArray, sourceHost: String, onVerified: suspend () -> Unit) {
        val parsed = WakeHello.parse(bytes) ?: return reject("unparsed", null)
        val local = loadLocalIdentity()
        if (parsed.senderId == local.deviceId) return
        val label = parsed.senderId.take(6) + if (parsed.isAck) " ack" else ""
        val sender = FileApexServices.deviceRepository.getDevice(parsed.senderId) ?: return reject("unknown sender", label)
        if (!WakeHello.verify(parsed, local.deviceId, sender, TimeUtils.now(), ClipboardPairAuthenticator)) {
            return reject("proof, time or removed sender", label)
        }
        val host = sourceHost.trim()
        if (!NetworkUtils.isUsableLanIpv4(host) || NetworkUtils.lanBindCandidates().none { NetworkUtils.sameIpv4Slash24(it, host) }) {
            return reject("source not on our LAN", label)
        }
        val gate = if (parsed.isAck) ackGate else helloGate
        if (!gate.accept(parsed.senderId, parsed.nonce)) return reject(REPEAT, label)
        onVerified()
        com.fileapex.iroh.IrohLog.add(
            (if (parsed.isAck) "ack from " else "hello from ") + "${sender.deviceName} verified at ${NetworkUtils.lastOctetLabel(host)}"
        )
        FileApexServices.presenceMonitor.onVerifiedHello(parsed.senderId, host, parsed.port)
        if (!parsed.isAck) {
            runCatching { WakeHelloOutbox.sendAck(sender, host) }
                .onFailure { println("WakeHelloReceiver: ack failed - ${it.message}") }
        }
    }

    private fun reject(reason: String, peer: String?) {
        if (reason != REPEAT && shouldShowReject(reason)) {
            com.fileapex.iroh.IrohLog.add("hello rejected: $reason" + (peer?.let { " ($it)" } ?: ""))
        }
    }

    private const val REPEAT = "repeat or too soon"
    private const val REJECT_LOG_GAP_MS = 60_000L
    private val rejectShownAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** One visible line per reason per minute, so packets from a stranger cannot fill the log. */
    private fun shouldShowReject(reason: String): Boolean {
        val now = TimeUtils.now()
        val last = rejectShownAt[reason]
        if (last != null && now - last < REJECT_LOG_GAP_MS) return false
        rejectShownAt[reason] = now
        return true
    }
}
