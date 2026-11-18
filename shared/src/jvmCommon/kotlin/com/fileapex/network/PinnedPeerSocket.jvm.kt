package com.fileapex.network

import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.tailscale.writeTailscaleDialToken
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** The first connect failure of one request, so the caller can tell "could not connect" from "the exchange failed". */
internal class ConnectFailure {
    @Volatile
    var error: Exception? = null
}

/**
 * The socket OkHttp talks to for a peer. Its [connect] does exactly what the hand-written client did: bind to the chosen
 * local address, let [PeerTlsConnector] decide between plain and pinned TLS for this peer, then write the tailnet dial
 * token. Everything else is the connected socket (plain or TLS) underneath.
 */
internal class PinnedPeerSocket(
    private val localIp: String,
    private val dialToken: String,
    private val failure: ConnectFailure
) : Socket() {
    @Volatile
    private var io: Socket? = null

    /** The plain socket under [io]. Closing it ends a blocked read or write at once and does no TLS work, which Android refuses on its main thread (a user's Cancel closes sockets from there). */
    @Volatile
    private var raw: Socket? = null
    @Volatile
    private var readTimeoutMs = 0
    @Volatile
    private var closed = false

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val address = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("peer address must be an InetSocketAddress")
        val plain = Socket()
        raw = plain
        try {
            if (localIp.isNotBlank()) plain.bind(InetSocketAddress(localIp, 0))
            val connected = PeerTlsConnector.connect(plain, address.hostString, address.port, timeout)
            writeTailscaleDialToken(connected.getOutputStream(), dialToken)
            connected.soTimeout = readTimeoutMs
            io = connected
            if (closed) plain.close()
        } catch (error: Exception) {
            try {
                plain.close()
            } catch (_: IOException) {
            }
            failure.error = error
            throw if (error is IOException) error else IOException("peer connect failed", error)
        }
    }

    private fun live(): Socket = io ?: throw IOException("not connected")

    override fun getInputStream(): InputStream = live().getInputStream()

    override fun getOutputStream(): OutputStream = live().getOutputStream()

    override fun setSoTimeout(timeout: Int) {
        readTimeoutMs = timeout
        io?.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = io?.soTimeout ?: readTimeoutMs

    override fun shutdownOutput() = PeerTlsConnector.shutdownOutput(live())

    override fun shutdownInput() = live().shutdownInput()

    override fun isConnected(): Boolean = io?.isConnected == true

    override fun isClosed(): Boolean = closed || io?.isClosed == true

    override fun isInputShutdown(): Boolean = io?.isInputShutdown == true

    override fun isOutputShutdown(): Boolean = io?.isOutputShutdown == true

    override fun getInetAddress(): InetAddress? = io?.inetAddress

    override fun getRemoteSocketAddress(): SocketAddress? = io?.remoteSocketAddress

    override fun getPort(): Int = io?.port ?: 0

    override fun getLocalPort(): Int = io?.localPort ?: -1

    override fun setTcpNoDelay(on: Boolean) {
        io?.tcpNoDelay = on
    }

    override fun setKeepAlive(on: Boolean) {
        io?.keepAlive = on
    }

    override fun close() {
        closed = true
        try {
            raw?.close()
        } finally {
            super.close()
        }
    }
}

/** Creates [PinnedPeerSocket]s for one request; [onSocket] sees each one so a transfer can put it on the cancel list. */
internal class PinnedPeerSocketFactory(
    private val localIp: String,
    private val dialToken: String,
    val failure: ConnectFailure,
    private val onSocket: (Socket) -> Unit = {}
) : SocketFactory() {
    override fun createSocket(): Socket = PinnedPeerSocket(localIp, dialToken, failure).also(onSocket)

    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()

    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
}
