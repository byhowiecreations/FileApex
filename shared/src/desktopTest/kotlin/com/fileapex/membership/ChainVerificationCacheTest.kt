package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A record's signature is checked once per process: the chain is read many times a minute and only new records are new work. */
class ChainVerificationCacheTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")

    @Before
    fun clean() = ChainRecords.resetVerificationCacheForTest()

    private suspend fun built(): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        return log
    }

    @Test
    fun eachRecordIsVerifiedOnceAcrossManyViews() = runBlocking {
        val cold = MembershipChainLog(InMemoryChainStore()).also { it.ingest(built().recordsAfter(0L)) }
        ChainRecords.resetVerificationCacheForTest()
        repeat(10) { cold.view() }
        assertEquals(2L, ChainRecords.verificationsForTest())
    }

    @Test
    fun ingestingKnownRecordsVerifiesNothingAgain() = runBlocking {
        val log = built()
        val records = log.recordsAfter(0L)
        log.view()
        val before = ChainRecords.verificationsForTest()
        log.ingest(records)
        log.view()
        assertEquals(before, ChainRecords.verificationsForTest())
    }

    @Test
    fun anotherLogOverTheSameRecordsSharesTheCache() = runBlocking {
        val first = built()
        first.view()
        val before = ChainRecords.verificationsForTest()
        val second = MembershipChainLog(InMemoryChainStore())
        second.ingest(first.recordsAfter(0L))
        second.view()
        assertEquals(before, ChainRecords.verificationsForTest())
    }

    @Test
    fun aBadSignatureIsNeverAccepted() = runBlocking {
        val log = built()
        val good = log.recordsAfter(0L).last()
        assertTrue(ChainRecords.verify(good))
        val forged = good.copy(signature = log.recordsAfter(0L).first().signature)
        assertFalse(ChainRecords.verify(forged))
        assertFalse(ChainRecords.verify(forged))
        val moved = good.copy(subject = "someone-else")
        assertFalse(ChainRecords.verify(moved))
    }

    @Test
    fun theCacheHasABound() = runBlocking {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        val genesis = log.recordsAfter(0L).single()
        repeat(ChainRecords.VERIFIED_LIMIT + 50) { n ->
            val signed = ChainRecords.sign(mac.signer, genesis.copy(reason = "paired-$n", signature = ""))
            assertTrue(ChainRecords.verify(signed))
        }
        assertTrue(ChainRecords.verifiedSizeForTest() <= ChainRecords.VERIFIED_LIMIT)
    }
}
