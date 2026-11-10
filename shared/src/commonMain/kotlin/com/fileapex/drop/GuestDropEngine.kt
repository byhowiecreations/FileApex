package com.fileapex.drop

import com.fileapex.iroh.IrohStream

/**
 * One Iroh endpoint with its own key and the drop protocol name only. It shares no key, router or handler with the paired
 * device link or with Friends, and it is relay-only: it publishes no direct address.
 */
interface GuestDropEndpoint {
    val isAlive: Boolean

    /** Hex of this endpoint's public key. */
    val id: String

    /** The relay this endpoint is reachable through, or null while it has none. */
    fun relayUrl(): String?

    /** Next stream from a visitor, or null once closed. */
    suspend fun accept(): IrohStream?

    suspend fun close()
}

interface GuestDropEngine {
    /**
     * [useRelay] is false only in tests that dial over loopback. [relayUrl] null uses the ordinary relays; a value makes the
     * endpoint use that relay alone, with no token (the relay lets in the ids the registry lists).
     */
    suspend fun start(secretKey: ByteArray, useRelay: Boolean = true, relayUrl: String? = null): GuestDropEndpoint
}

/** Null in builds without Iroh's native library. */
expect fun embeddedGuestDropEngine(): GuestDropEngine?
