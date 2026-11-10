package com.fileapex.membership

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.20.10 (one trust path): a device on the chain leaves the roster only through an accepted REMOVE. */
class LocalRemovalTest {
    private val state = ChainState(3L, "h", mapOf("me" to ChainMember("k", "", true), "peer" to ChainMember("kp", "", true)))

    @Test
    fun aChainMemberIsRemovedLocallyOnlyWhenTheChainTookTheRemoval() {
        assertTrue(state.localRemovalAllowed("peer", ChainVerdict.Accepted(state)))
        assertFalse(state.localRemovalAllowed("peer", ChainVerdict.Rejected("the actor is not an active member with this key")))
        assertFalse(state.localRemovalAllowed("peer", null))
    }

    @Test
    fun aPeerTheChainNeverNamedIsAPairwiseUnpair() {
        assertTrue(state.localRemovalAllowed("old-build-peer", ChainVerdict.Rejected("not in the chain")))
        assertTrue(ChainState.EMPTY.localRemovalAllowed("peer", null))
    }
}
