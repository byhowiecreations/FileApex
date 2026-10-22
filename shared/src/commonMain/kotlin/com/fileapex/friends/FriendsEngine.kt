package com.fileapex.friends

import com.fileapex.iroh.IrohStream

class FriendsInbound(val peerId: String, val stream: IrohStream)

/** One Iroh endpoint with the friends key and the friends ALPN only. */
interface FriendsEndpoint {
    /** Hex of the friends public key. */
    val id: String

    /** `id|relay|direct,direct` for a friend to dial; without direct addresses when the IP is hidden. */
    fun address(): String

    /** Next stream from an admitted peer, or null once closed. */
    suspend fun accept(): FriendsInbound?

    /** A new stream to [peerId]. [addressHint] is an `address()` string when known, otherwise the peer is looked up by key. */
    suspend fun open(peerId: String, addressHint: String?): IrohStream

    fun pathKind(peerId: String): String?

    suspend fun close()
}

interface FriendsEngine {
    /**
     * [admit] runs right after the handshake, before any stream is read; a false answer closes the connection.
     * [hideIp] keeps this device off direct paths so a friend only ever sees the relay.
     */
    suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (peerId: String) -> Boolean): FriendsEndpoint
}

/** Null in builds without Iroh's native library. */
expect fun embeddedFriendsEngine(): FriendsEngine?
