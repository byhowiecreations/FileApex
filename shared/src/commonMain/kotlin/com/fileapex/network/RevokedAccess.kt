package com.fileapex.network

/**
 * What a removed device may still ask a peer that recognises its old key. Every other request is answered as revoked.
 * The chain is signed and holds only device ids, keys and pins, so reading it gives the removed device the record that
 * proves its removal and nothing it could act on.
 */
internal object RevokedAccess {
    private const val CHAIN_PATH = "/api/v1/membership/chain"

    /** The device-id revocation gate answers `401 revoked` to everything but what a removed device may read. */
    fun gatedAsRevoked(method: String, uri: String): Boolean = !allows(method, uri)

    fun allows(method: String, uri: String): Boolean = method.equals("GET", ignoreCase = true) && uri.substringBefore('?') == CHAIN_PATH
}
