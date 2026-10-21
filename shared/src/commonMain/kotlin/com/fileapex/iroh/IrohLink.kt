package com.fileapex.iroh

interface IrohStream {
    /** Up to [max] bytes, or null once the peer has finished sending. */
    suspend fun read(max: Int): ByteArray?

    suspend fun write(bytes: ByteArray)

    /** No more bytes from this side. The peer can keep sending. */
    suspend fun finish()

    /** Drops both directions at once. Safe to call twice. */
    fun abort()
}

class IrohInbound(val peerId: String, val stream: IrohStream)

interface IrohLink {
    /** The next inbound stream, or null once the link is closed. */
    suspend fun accept(): IrohInbound?

    suspend fun open(peerId: String): IrohStream

    suspend fun close()
}

interface IrohLinkHandle : IrohLink {
    /** Hex of the endpoint's public key. */
    val id: String

    /** `id|relayUrl|direct,direct` for peers to dial. */
    fun address(): String

    /** Remembers how to reach a peer and returns its id. */
    fun addPeer(address: String): String

    /** True while a connection to [peerId] is open with a selected path. Needs no network round trip. */
    fun isConnected(peerId: String): Boolean

    /** Connects to [peerId] without opening a stream, so a failure shows up before any request. */
    suspend fun connect(peerId: String)

    /** Description of the live paths to [peerId] (direct or relay), or null with no connection. */
    fun pathKind(peerId: String): String?

    /** Calls [onChange] whenever this endpoint's own address changes (new network, new home relay). */
    fun onAddressChanged(onChange: () -> Unit)

    /** Looks at the address now instead of at the next slow check; a network change calls it. */
    fun checkAddressNow() = Unit
}

interface IrohEngine {
    /**
     * Starts an endpoint that keeps the identity in [secretKey] (32 bytes). With [relay] set the endpoint uses
     * only that relay; otherwise n0's public relays.
     */
    suspend fun start(secretKey: ByteArray, relay: IrohRelayConfig? = null): IrohLinkHandle
}

/** Null where Iroh is not built in (F-Droid) or its native library cannot load. */
expect fun embeddedIrohEngine(): IrohEngine?
