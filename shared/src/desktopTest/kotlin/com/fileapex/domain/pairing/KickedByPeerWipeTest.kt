package com.fileapex.domain.pairing

import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.di.FileApexServices
import com.fileapex.membership.ChainBootstrap
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import com.fileapex.membership.ChainTransport
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.InMemoryChainStore
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.MembershipHub
import com.fileapex.membership.MembershipKeys
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device that lets go of its cluster must also drop the signed chain, or the next pairing forks against the old genesis. */
class KickedByPeerWipeTest {
    @After
    fun restore() {
        FileApexServices.installMembershipForTest(null, null)
    }

    @Test
    fun wipingTheClusterClearsTheMembershipChainSoTheNextPairingStartsClean() = runBlocking {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner("mac", pair.private, pair.public)
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<com.fileapex.membership.ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>) = null
            override suspend fun pull(peerId: String) = null
            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?) = null
        }
        val replicator = ChainReplicator(log, transport)
        val bootstrap = ChainBootstrap(log) { signer }
        assertTrue(bootstrap.createGenesisIfLowest(setOf("mac"), MembershipKeys.pin(signer.publicKey)))
        assertTrue(log.view().canonical.isNotEmpty())
        val repo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef("mac", emptySet()) })
        FileApexServices.installMembershipForTest(repo, MembershipHub(log, replicator, ChainTick("mac", log, replicator, transport, bootstrap, { signer }), { setOf("mac") }) {})
        KickedByPeer.wipeCluster()
        assertEquals(0L, log.view().state.seq)
        assertTrue(log.view().canonical.isEmpty())
    }

    @Test
    fun wipingTheClusterAlsoClearsTheClustersData() = runBlocking {
        val repo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef("mac", emptySet()) })
        FileApexServices.installMembershipForTest(repo, null)
        var purged = false
        val original = KickedByPeer.purgeData
        KickedByPeer.purgeData = { purged = true }
        try { KickedByPeer.wipeCluster() } finally { KickedByPeer.purgeData = original }
        assertTrue(purged)
    }
}
