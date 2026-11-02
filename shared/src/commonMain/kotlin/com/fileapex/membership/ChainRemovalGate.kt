package com.fileapex.membership

/**
 * Removals that arrive in the old unsigned format (removal lists, tombstone gossip) have no authority. One is believed
 * only when the signed chain already holds the device as removed or departed; a build without the chain cannot make one.
 */
class ChainRemovalGate(private val state: suspend () -> ChainState?) {
    suspend fun confirms(deviceId: String): Boolean = state()?.member(deviceId)?.active == false
}
