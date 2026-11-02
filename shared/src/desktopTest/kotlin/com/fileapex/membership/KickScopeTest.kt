package com.fileapex.membership

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KickScopeTest {
    private fun member(key: String, active: Boolean = true) =
        ChainMember(key, "a".repeat(64), active)

    @Test
    fun aRemoveIsAClusterKickWhenOtherMembersRemain() {
        val state = ChainState(
            3L, "h",
            mapOf(
                "mac" to member("k-mac"),
                "phone" to member("k-phone", active = false),
                "tablet" to member("k-tablet")
            )
        )
        assertTrue(state.removalIsClusterKick("mac", "phone"))
    }

    @Test
    fun aTwoDeviceRemoveIsAClusterKickWhenTheSubjectJoinedWithARealKey() {
        val state = ChainState(
            2L, "h",
            mapOf(
                "mac" to member("k-mac"),
                "phone" to member("k-phone", active = false)
            )
        )
        assertTrue(state.removalIsClusterKick("mac", "phone"))
    }

    @Test
    fun aPairwiseRemoveOfALegacyPeerIsNotAClusterKick() {
        val state = ChainState(
            2L, "h",
            mapOf(
                "nothing" to member("k-nothing"),
                "signature" to member("legacy:signature", active = false)
            )
        )
        assertFalse(state.removalIsClusterKick("nothing", "signature"))
    }

    @Test
    fun aPairwiseRemoveOfALegacyPeerIsNotAClusterKickEvenWhenOtherMembersRemain() {
        val state = ChainState(
            10L, "h",
            mapOf(
                "nothing" to member("k-nothing"),
                "mac" to member("k-mac"),
                "signature" to member("legacy:signature", active = false)
            )
        )
        assertFalse(state.removalIsClusterKick("nothing", "signature"))
    }

    @Test
    fun aRemoveWithNoChainIsNotAClusterKick() {
        assertFalse(ChainState.EMPTY.removalIsClusterKick("nothing", "signature"))
    }

    @Test
    fun aLegacyMemberCannotCommandMembershipEvenWhenActive() {
        val state = ChainState(
            10L, "h",
            mapOf(
                "mac" to member("k-mac"),
                "signature" to member("legacy:signature")
            )
        )
        assertTrue(state.memberCanCommandMembership("mac"))
        assertFalse(state.memberCanCommandMembership("signature"))
        assertFalse(state.memberCanCommandMembership("unknown"))
        assertFalse(ChainState.EMPTY.memberCanCommandMembership("mac"))
    }
}
