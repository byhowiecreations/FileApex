package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.MembershipSchemaCallback
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainBootstrap
import com.fileapex.membership.ChainProfiles
import com.fileapex.membership.ChainRecord
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import com.fileapex.membership.ChainTransport
import com.fileapex.membership.ChainVerdict
import com.fileapex.membership.IdentityChangeProof
import com.fileapex.security.IdentityBinding
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A device keeps its TLS and signing keys but gets a new id: the member's real roster follows the two chain records. */
class IdentityChangeRosterTest {
    private lateinit var db: FileApexDatabase
    private lateinit var seed: ChainSeed
    private val e2ee = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val newId = IdentityBinding.deriveDeviceId(e2ee)

    private val noNetwork = object : ChainTransport {
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? = null
        override suspend fun pull(peerId: String): ChainAnswer? = null
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
    }

    @Before
    fun setUp() {
        val file = File.createTempFile("idchange", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).addCallback(MembershipSchemaCallback()).build()
        seed = ChainSeed(db, selfId = "mac")
        runBlocking { seed.start(); seed.admit("fx-old"); seed.admit("tablet") }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    private fun tick(): ChainTick {
        val signer = seed.dev("mac").signer
        val replicator = ChainReplicator(seed.log, noNetwork, profiles = object : ChainProfiles {
            override suspend fun own() = PeerNodeState(deviceId = "mac", deviceName = "m", ipAddress = "10.0.0.1", port = 8080, publicKey = "k", tlsPort = 8443)
            override suspend fun outgoing() = listOf(own())
            override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) = Unit
        })
        val applier = com.fileapex.membership.ChainApplier(
            "mac", RoomChainEffects(com.fileapex.data.db.RoomTlsPinStore(db.tlsPinDao()), db.membershipDao(), onPeerRemoved = {}, invalidateTombstones = {})
        )
        return ChainTick("mac", seed.log, replicator, noNetwork, ChainBootstrap(seed.log) { signer }, { signer }, onChanged = { applier.apply(seed.log.view()) })
    }

    @Test fun afterTheConfirmationTheOldIdIsRemovedAndTheNewOneIsActiveOnTheSamePin() = runBlocking {
        val honor = seed.dev("fx-old")
        val request = IdentityChangeProof.request(honor.signer, "fx-old", newId, e2ee, "hw", "n1")
        assertTrue(tick().confirmIdentityChange(request) is ChainVerdict.Accepted)
        seed.refresh()
        val dao = db.membershipDao()
        assertEquals("REMOVED", dao.get("fx-old")!!.state)
        assertEquals("ACTIVE", dao.get(newId)!!.state)
        assertEquals(honor.pin, db.tlsPinDao().member(newId)!!.tlsPin)
        assertEquals("ACTIVE", dao.get("tablet")!!.state)
    }

    @Test fun nothingChangesWhenTheChainRefusesTheRequest() = runBlocking {
        val stranger = seed.dev("fx-stranger")
        val request = IdentityChangeProof.request(stranger.signer, "fx-stranger", newId, e2ee, "hw", "n1")
        assertTrue(tick().confirmIdentityChange(request) is ChainVerdict.Rejected)
        seed.refresh()
        assertEquals("ACTIVE", db.membershipDao().get("fx-old")!!.state)
        assertEquals(null, db.membershipDao().get(newId))
    }
}
