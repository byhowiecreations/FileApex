package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainBootstrapTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val amanda = Dev("amanda")
    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val roster = setOf("amanda", "mac", "phone")

    private fun bootstrap(dev: Dev, log: MembershipChainLog = MembershipChainLog(InMemoryChainStore())) =
        log to ChainBootstrap(log, { dev.signer })

    @Test
    fun theDeviceWithTheLowestIdOfTheRosterCreatesTheGenesis() = runBlocking {
        val (log, b) = bootstrap(amanda)
        assertTrue(b.createGenesisIfLowest(roster, amanda.pin))
        assertEquals(listOf("amanda"), log.view().state.activeIds())
    }

    @Test
    fun otherDevicesWaitForTheGenesisAndNeverMakeASecondOne() = runBlocking {
        val (log, b) = bootstrap(mac)
        assertFalse(b.createGenesisIfLowest(roster, mac.pin))
        assertEquals(0L, log.view().state.seq)
    }

    @Test
    fun noGenesisIsMadeOnceTheChainExists() = runBlocking {
        val (log, b) = bootstrap(amanda)
        b.createGenesisIfLowest(roster, amanda.pin)
        assertFalse(b.createGenesisIfLowest(roster, amanda.pin))
        assertEquals(1L, log.view().state.seq)
    }

    @Test
    fun aMemberAdmitsARosterDeviceThatAsksWithItsSigningKey() = runBlocking {
        val (log, b) = bootstrap(amanda)
        b.createGenesisIfLowest(roster, amanda.pin)
        assertTrue(b.admit("mac", mac.key, mac.pin, roster) is ChainVerdict.Accepted)
        assertEquals(listOf("amanda", "mac"), log.view().state.activeIds().sorted())
        assertEquals(mac.key, log.view().state.member("mac")!!.key)
    }

    @Test
    fun aDeviceThatIsNotOnTheRosterIsNotAdmitted() = runBlocking {
        val (log, b) = bootstrap(amanda)
        b.createGenesisIfLowest(roster, amanda.pin)
        val stranger = Dev("stranger")
        assertTrue(b.admit("stranger", stranger.key, stranger.pin, roster) is ChainVerdict.Rejected)
        assertEquals(1L, log.view().state.seq)
    }

    @Test
    fun aDeviceAlreadyInTheChainIsNotAdmittedTwice() = runBlocking {
        val (_, b) = bootstrap(amanda)
        b.createGenesisIfLowest(roster, amanda.pin)
        b.admit("mac", mac.key, mac.pin, roster)
        assertTrue(b.admit("mac", mac.key, mac.pin, roster) is ChainVerdict.Rejected)
    }

    @Test
    fun admissionBeforeAnyGenesisIsRejected() = runBlocking {
        val (_, b) = bootstrap(amanda)
        assertTrue(b.admit("mac", mac.key, mac.pin, roster) is ChainVerdict.Rejected)
    }
}
