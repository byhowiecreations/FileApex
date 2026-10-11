package com.fileapex.security

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.PairCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIntroductionsTest {
    /** Only the pair that sealed it can open it. */
    private class FakeCrypto(private val ownKey: String) : PairCrypto {
        override fun publicKeyBase64() = ownKey
        override fun encrypt(plaintext: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String) =
            listOf(localDeviceId, peerDeviceId, plaintext.decodeToString()).joinToString("|")

        override fun decrypt(ciphertextBase64: String, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): ByteArray {
            val (from, to, body) = ciphertextBase64.split("|", limit = 3)
            require(from == peerDeviceId && to == localDeviceId) { "wrong pair" }
            return body.encodeToByteArray()
        }
    }

    private val now = 1_800_000_000_000L

    private fun row(id: String, key: String = "key-$id", version: Long = now) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "192.168.1.${id.length + 20}", port = 8080,
        publicKeyHash = "h-$id", publicKey = key, rootPath = "/", clusterVersion = version,
        lastSeenEpochMs = now, deviceMake = "HONOR", deviceModel = "MTN-NX1"
    )

    private val introducer = row("introducer")
    private val recipient = row("recipient")
    private val subject = row("subject")

    private fun sealed() = PeerIntroductions.seal("introducer", recipient, subject, now, FakeCrypto("key-introducer"))!!

    @Test
    fun recipientOpensWhatItsIntroducerSealed() {
        val result = PeerIntroductions.open(sealed(), introducer, "recipient", now + 1000, FakeCrypto("key-recipient"))
        val accepted = result as IntroductionResult.Accepted
        assertEquals("subject", accepted.introduction.subject.deviceId)
        assertEquals("introducer", accepted.introduction.introducerId)
    }

    @Test
    fun presenceAndTlsPinNeverTravelInAnIntroduction() {
        val result = PeerIntroductions.open(sealed(), introducer, "recipient", now, FakeCrypto("key-recipient"))
        val subject = (result as IntroductionResult.Accepted).introduction.subject
        assertEquals(0L, subject.lastSeenTimestamp)
        assertEquals("", subject.tlsPin)
    }

    @Test
    fun anUnknownOrRemovedIntroducerIsRefused() {
        val crypto = FakeCrypto("key-recipient")
        assertEquals(
            IntroductionRejection.UnknownIntroducer,
            (PeerIntroductions.open(sealed(), null, "recipient", now, crypto) as IntroductionResult.Rejected).reason
        )
        assertEquals(
            IntroductionRejection.UnknownIntroducer,
            (PeerIntroductions.open(sealed(), introducer.copy(isRemoved = true), "recipient", now, crypto) as IntroductionResult.Rejected).reason
        )
    }

    @Test
    fun aKeyThatIsNotTheStoredOneIsRefused() {
        val forged = sealed().copy(introducerPublicKey = "someone-elses-key")
        val result = PeerIntroductions.open(forged, introducer, "recipient", now, FakeCrypto("key-recipient"))
        assertEquals(IntroductionRejection.IntroducerKeyMismatch, (result as IntroductionResult.Rejected).reason)
    }

    @Test
    fun anotherDeviceCannotOpenAnIntroductionAddressedElsewhere() {
        val result = PeerIntroductions.open(sealed(), introducer, "bystander", now, FakeCrypto("key-bystander"))
        assertEquals(IntroductionRejection.Undecryptable, (result as IntroductionResult.Rejected).reason)
    }

    @Test
    fun staleOrFutureIntroductionsAreRefused() {
        val crypto = FakeCrypto("key-recipient")
        val late = PeerIntroductions.open(sealed(), introducer, "recipient", now + PeerIntroductions.MAX_AGE_MS + 1, crypto)
        assertEquals(IntroductionRejection.Stale, (late as IntroductionResult.Rejected).reason)
        val early = PeerIntroductions.open(sealed(), introducer, "recipient", now - 3_600_000L, crypto)
        assertEquals(IntroductionRejection.Stale, (early as IntroductionResult.Rejected).reason)
    }

    @Test
    fun nothingIsSealedForARecipientWithoutAKeyOrAboutItself() {
        assertNull(PeerIntroductions.seal("introducer", recipient.copy(publicKey = ""), subject, now, FakeCrypto("k")))
        assertNull(PeerIntroductions.seal("introducer", recipient, recipient, now, FakeCrypto("k")))
        assertNotNull(sealed())
        assertTrue(sealed().ciphertext.isNotEmpty())
    }
}
