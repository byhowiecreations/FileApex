package com.fileapex.tailscale

/**
 * Embedded userspace node. Implementations must not open a system VPN interface.
 */
interface TailscaleUserspaceEngine {
    suspend fun start(authKey: String): TailscaleLinkSnapshot
    suspend fun stop()
    suspend fun clearState()

    /** Expires the node at Tailscale. Null on success. Does not delete the local store. */
    suspend fun logout(): String?
    fun poll(): TailscaleLinkSnapshot
    fun activeConnections(): Int
    /** Null when the peer list could not be read. An empty tailnet is "[]". */
    fun tailnetPeersJson(): String?
}

internal data class TailscaleLoopbackDial(
    val host: String,
    val port: Int,
    val token: String
)

/** Written on the local splice before any HTTP bytes. Empty tokens send nothing. */
fun writeTailscaleDialToken(output: java.io.OutputStream, token: String) {
    if (token.isEmpty()) return
    output.write(token.encodeToByteArray())
    output.write('\n'.code)
}

/** Keeps the existing share-server foreground service alive on Android. No-op on desktop. */
expect fun holdTailscaleProcess()

/** Opens the login page in the system default browser. */
expect fun openTailscaleLoginInBrowser(url: String)

/** Brings FileApex forward after the browser login registers the node. */
expect fun returnToAppAfterTailscaleLogin()

/** tailscaled.state. A local read, with no network call. */
expect fun tailscaleNodeStateFile(): java.io.File

expect fun embeddedTsnetEngine(): TailscaleUserspaceEngine
