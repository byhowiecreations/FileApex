package com.fileapex.network

import com.fileapex.util.NetworkUtils
import java.io.IOException
import java.security.cert.CertificateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the signed membership chain from a peer whose TLS key does not match the pinned one, which is what a peer that
 * changed its key looks like to a device that was away. Only the chain path can be fetched this way and only with GET:
 * the answer is a list of signed records that is checked record by record, so the connection proves nothing and nothing
 * else is ever sent over it. No pin is stored and no key-change prompt is raised.
 */
internal object UnpinnedFetch {
    const val CHAIN_PATH = "/api/v1/membership/chain"

    /** Null when the peer could not be reached or refused this device. */
    suspend fun get(host: String, tlsPort: Int, path: String, timeoutMs: Long): PeerBoundHttpResponse? = withContext(Dispatchers.IO) {
        require(path == CHAIN_PATH) { "only the chain can be fetched without a pinned key" }
        try {
            val (ssl, _) = TlsExchange.connect(host, tlsPort, timeoutMs, "", NetworkUtils.orderBindCandidatesForPeer(host))
            ssl.use {
                val head = "GET $path HTTP/1.1\r\nHost: $host:$tlsPort\r\nConnection: close\r\n\r\n"
                it.outputStream.apply { write(head.encodeToByteArray()); flush() }
                val reply = TlsExchange.readResponse(it)
                if (reply.status == 0) null else PeerBoundHttpResponse(reply.status, reply.body)
            }
        } catch (e: CertificateException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
