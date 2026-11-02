package com.fileapex.domain.peer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerNodeProtocolsTest {
    @Test
    fun thisBuildAdvertisesTheChain() {
        assertTrue(PeerNodeProtocols.MEMBERSHIP_CHAIN_V1 in PeerNodeProtocols.DEFAULT)
    }

    @Test
    fun aListThatLacksTheChainIsAnOldBuildOnlyWhenTheListIsKnown() {
        val old = listOf(PeerNodeProtocols.IDENTITY_V1, PeerNodeProtocols.FILES_V1, PeerNodeProtocols.CLUSTER_V1)
        assertTrue(PeerNodeProtocols.knownToLackChain(old))
        assertFalse(PeerNodeProtocols.knownToLackChain(emptyList()))
        assertFalse(PeerNodeProtocols.knownToLackChain(PeerNodeProtocols.DEFAULT))
    }
}
