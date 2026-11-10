package com.fileapex.cloud.seal

import com.fileapex.di.FileApexServices
import com.fileapex.membership.ChainState

/**
 * Who may be given the cloud record key. Only a member that is active in this device's chain, and only once this
 * device's chain has reached the removal the key was made for, so a device that has not applied a removal yet cannot
 * pass the new key to the device it removes. Without a chain (a pairing before the chain exists) the key goes as before.
 */
object ClusterKeyHandover {
    fun mayHand(state: ChainState, recipientId: String, key: ClusterRecordKey): Boolean {
        if (state.seq == 0L) return true
        return state.member(recipientId)?.active == true && state.seq >= key.chainSeq
    }

    /** The key as JSON for [recipientId], or blank when it may not be handed over. */
    suspend fun forRecipient(recipientId: String): String {
        val key = ClusterRecordKeys.current() ?: return ""
        val state = FileApexServices.membershipHub?.chainLog?.view()?.state ?: ChainState.EMPTY
        return if (mayHand(state, recipientId, key)) key.toJson() else ""
    }
}
