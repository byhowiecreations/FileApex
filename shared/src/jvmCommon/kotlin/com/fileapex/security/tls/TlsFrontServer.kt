package com.fileapex.security.tls

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Accepts a client certificate only when its public key pin belongs to a paired device. Dates are ignored.
 * [allowUnpinned] lets a key nobody has paired yet complete the handshake (while this device shows its pairing
 * screen); [TlsFrontServer] then limits such a connection to the secure pairing route.
 */
class PinningClientTrustManager(
    private val directory: TlsPeerDirectory,
    private val allowUnpinned: () -> Boolean = { false }
) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = unsupported()
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = unsupported()
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = unsupported()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun unsupported(): Nothing = throw CertificateException("server verification is not handled here")

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no client certificate")
        if (leaf.publicKey.algorithm != "EC") throw CertificateException("unsupported key type")
        if (directory.deviceIdForPin(TlsPin.of(leaf.publicKey)) == null && !allowUnpinned()) {
            throw CertificateException("client key is not pinned")
        }
    }
}

/**
 * TLS listener in front of the Ktor CIO server, which cannot terminate TLS. Each accepted connection is
 * authenticated by client-certificate pin, registered in [TlsBridgeRegistry] under its loopback source
 * port, and piped to [bridgePort] with bounded buffers. A connection with no traffic in either direction for
 * [idleTimeoutMs] is closed, so a silent peer cannot hold a socket and a thread forever.
 */
class TlsFrontServer(
    private val identity: () -> TlsIdentity,
    private val directory: TlsPeerDirectory,
    private val bindHost: String,
    private val preferredPort: Int,
    override val bridgePort: Int,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val sweepIntervalMs: Long = SWEEP_INTERVAL_MS,
    private val allowUnpinned: () -> Boolean = { false },
    private val log: (String, Throwable?) -> Unit
) : TlsFront {
    private val running = AtomicBoolean(false)
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val threadIds = AtomicInteger()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "tls-front-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }
    private val sessions = java.util.concurrent.ConcurrentHashMap<SSLSocket, BridgedPeer>()
    private val lastActivity = java.util.concurrent.ConcurrentHashMap<SSLSocket, AtomicLong>()
    private var sweeper: java.util.concurrent.ScheduledExecutorService? = null
    private var server: SSLServerSocket? = null

    override fun start(): Int {
        check(running.compareAndSet(false, true)) { "TLS front already started" }
        try {
            val socket = createServerSocket()
            server = socket
            pool.execute { acceptLoop(socket) }
            sweeper = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "tls-front-idle").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay(::closeIdle, sweepIntervalMs, sweepIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            log("TLS front listening on port ${socket.localPort}, bridge ${bridgePort}", null)
            return socket.localPort
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        sweeper?.shutdownNow()
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
        pool.shutdownNow()
        log("TLS front stopped", null)
    }

    private fun closeIdle() {
        val limitNanos = idleTimeoutMs * 1_000_000L
        val now = System.nanoTime()
        for ((socket, last) in lastActivity) {
            if (now - last.get() > limitNanos) {
                log("TLS connection closed after ${idleTimeoutMs / 1000} s with no traffic", null)
                runCatching { socket.close() }
            }
        }
    }

    /** Closes connections whose device or pin is no longer in the directory (unpaired, re-keyed). */
    fun revalidate() {
        for ((socket, peer) in sessions) {
            if (!peer.pairingOnly && directory.deviceIdForPin(peer.pin) != peer.deviceId) {
                log("TLS connection dropped: peer ${peer.deviceId} no longer pinned", null)
                runCatching { socket.close() }
            }
        }
    }

    private fun createServerSocket(): SSLServerSocket {
        val id = identity()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tls", id.privateKey, KEY_PASSWORD, arrayOf(id.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, KEY_PASSWORD) }.keyManagers
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, arrayOf<TrustManager>(PinningClientTrustManager(directory, allowUnpinned)), null)
        val address = InetAddress.getByName(bindHost)
        val factory = context.serverSocketFactory
        val socket = try {
            factory.createServerSocket(preferredPort, BACKLOG, address) as SSLServerSocket
        } catch (e: IOException) {
            log("TLS port $preferredPort unavailable, using an ephemeral port", e)
            factory.createServerSocket(0, BACKLOG, address) as SSLServerSocket
        }
        socket.needClientAuth = true
        socket.enabledProtocols = preferredProtocols(socket.supportedProtocols)
        return socket
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept() as SSLSocket
            } catch (e: IOException) {
                if (running.get()) log("TLS accept failed", e)
                return
            }
            if (!slots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                log("TLS connection refused: ${e.javaClass.simpleName}", null)
                slots.release()
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: SSLSocket) {
        var upstream: Socket? = null
        var sourcePort = -1
        try {
            open.add(client)
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.startHandshake()
            val leaf = client.session.peerCertificates.firstOrNull() as? X509Certificate ?: run {
                log("TLS client sent no certificate", null)
                return
            }
            val pin = TlsPin.of(leaf.publicKey)
            val known = directory.deviceIdForPin(pin)
            if (known == null && !allowUnpinned()) {
                log("TLS client key ${TlsPin.forLog(pin)} is not pinned", null)
                return
            }
            val deviceId = known.orEmpty()
            client.soTimeout = 0
            client.tcpNoDelay = true
            client.keepAlive = true
            val up = Socket(BRIDGE_ADDRESS, bridgePort).also { upstream = it; open.add(it) }
            up.tcpNoDelay = true
            sourcePort = up.localPort
            val peer = BridgedPeer(deviceId, pin, client.inetAddress.hostAddress.orEmpty().substringBefore('%'), pairingOnly = known == null)
            TlsBridgeRegistry.register(sourcePort, peer)
            sessions[client] = peer
            val activity = AtomicLong(System.nanoTime()).also { lastActivity[client] = it }
            val toServer = pool.submit { pipe(client, up, closeTargetOutputOnly = true, activity) }
            pipe(up, client, closeTargetOutputOnly = false, activity)
            toServer.cancel(true)
        } catch (e: Exception) {
            if (running.get()) log("TLS connection ended: ${e.javaClass.simpleName}", if (e is IOException) null else e)
        } finally {
            if (sourcePort > 0) TlsBridgeRegistry.unregister(sourcePort)
            sessions.remove(client)
            lastActivity.remove(client)
            runCatching { client.close() }
            runCatching { upstream?.close() }
            open.remove(client)
            upstream?.let(open::remove)
            slots.release()
        }
    }

    private fun pipe(from: Socket, to: Socket, closeTargetOutputOnly: Boolean, activity: AtomicLong) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                activity.set(System.nanoTime())
                output.write(buffer, 0, read)
                activity.set(System.nanoTime())
            }
            if (closeTargetOutputOnly) runCatching { to.shutdownOutput() } else runCatching { to.close() }
        } catch (_: IOException) {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    internal companion object {
        /** The bridge connector listens on 127.0.0.1; Android's getLoopbackAddress() can return ::1. */
        val BRIDGE_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val MAX_CONNECTIONS = 64
        const val BACKLOG = 50
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
        const val SWEEP_INTERVAL_MS = 30_000L
        const val BUFFER_BYTES = 64 * 1024
        private val KEY_PASSWORD = "fileapex".toCharArray()

        /** TLS 1.3 where the platform has it. Android 8 to 9 only offer 1.2; pin and client auth still apply. */
        fun preferredProtocols(supported: Array<String>): Array<String> =
            if ("TLSv1.3" in supported) arrayOf("TLSv1.3") else arrayOf("TLSv1.2")
    }
}

/** Pin lookup backed by the paired-device roster. Refreshed whenever the roster changes. */
class RosterTlsPeerDirectory(private val snapshot: () -> List<com.fileapex.data.db.PairedDeviceEntity>) : TlsPeerDirectory {
    @Volatile
    private var byPin: Map<String, String> = emptyMap()

    fun refresh(devices: List<com.fileapex.data.db.PairedDeviceEntity> = snapshot()) {
        val map = HashMap<String, String>()
        for (device in devices) {
            if (device.isRemoved) continue
            if (device.tlsPin.isNotEmpty()) map[device.tlsPin] = device.deviceId
            if (device.tlsPinAlt.isNotEmpty()) map[device.tlsPinAlt] = device.deviceId
        }
        byPin = map
    }

    override fun deviceIdForPin(pin: String): String? = byPin[pin.lowercase()]
}
