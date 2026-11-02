package com.fileapex.security.tls

import com.fileapex.membership.ChainRecord
import com.fileapex.membership.ChainRecords
import com.fileapex.membership.MembershipKeys
import com.fileapex.membership.RecordKind
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SigningKeyStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class AesVault(override val scheme: Int, private val key: javax.crypto.SecretKey) : TlsSecretVault {
        override fun seal(plain: ByteArray) = GcmBlob.sealWithRandomIv(key, plain)
        override fun open(sealed: ByteArray) = GcmBlob.open(key, sealed)
    }

    private class FailingVault(override val scheme: Int = 9) : TlsSecretVault {
        override fun seal(plain: ByteArray): ByteArray = error("boom")
        override fun open(sealed: ByteArray): ByteArray = error("boom")
    }

    private fun dir() = File(temp.root, "sign")

    @Test
    fun theKeyIsStableAcrossRestarts() {
        val first = SigningKeyStore(dir(), emptyList()).getOrCreate()
        val second = SigningKeyStore(dir(), emptyList()).getOrCreate()
        assertArrayEquals(first.public.encoded, second.public.encoded)
    }

    @Test
    fun theFileIsSealedByTheVaultAndRoundTrips() {
        val vault = AesVault(7, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val created = SigningKeyStore(dir(), listOf(vault)).getOrCreate()
        val raw = File(dir(), "signing_key.v1").readBytes()
        assertEquals(7, raw[0].toInt())
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(created.private.encoded.copyOfRange(0, 16), Charsets.ISO_8859_1)))
        assertArrayEquals(created.public.encoded, SigningKeyStore(dir(), listOf(vault)).getOrCreate().public.encoded)
    }

    @Test
    fun aFailingVaultFallsBackToPlainLikeTheTlsIdentity() {
        val created = SigningKeyStore(dir(), listOf(FailingVault())).getOrCreate()
        assertEquals(0, File(dir(), "signing_key.v1").readBytes()[0].toInt())
        assertArrayEquals(created.public.encoded, SigningKeyStore(dir(), listOf(FailingVault())).getOrCreate().public.encoded)
    }

    @Test
    fun aKeyThatCannotBeReadIsNeverReplacedSilently() {
        SigningKeyStore(dir(), emptyList()).getOrCreate()
        File(dir(), "signing_key.v1").writeBytes(byteArrayOf(0, 1, 2, 3))
        try {
            SigningKeyStore(dir(), emptyList()).getOrCreate()
            fail("a damaged key must not be replaced without the user knowing")
        } catch (_: TlsIdentityUnavailableException) {
        }
    }

    @Test
    fun theSignerSignsChainRecordsThatVerify() {
        val store = SigningKeyStore(dir(), emptyList())
        val signer = store.signer("mac")
        val key = signer.publicKey
        val genesis = ChainRecords.sign(signer, ChainRecord(1, "", RecordKind.GENESIS, "mac", key, "mac", key, MembershipKeys.pin(key), "paired"))
        assertTrue(ChainRecords.verify(genesis))
    }

    @Test
    fun theSigningKeyIsNotTheTlsKey() {
        val tls = TlsIdentityStore(File(temp.root, "tls"), emptyList()).getOrCreate()
        val signing = SigningKeyStore(dir(), emptyList()).getOrCreate()
        assertNotEquals(tls.certificate.publicKey.encoded.toList(), signing.public.encoded.toList())
    }

    @Test
    fun aRejoiningDeviceGetsAFreshSigningKey() {
        val store = SigningKeyStore(dir(), emptyList())
        val before = store.getOrCreate().public.encoded
        val after = store.replaceForRejoin().public.encoded
        assertNotEquals(before.toList(), after.toList())
        assertArrayEquals(after, SigningKeyStore(dir(), emptyList()).getOrCreate().public.encoded)
    }

    @Test
    fun theTlsStoreHandsOutOneSigningKeyStoreKeptNextToItsIdentity() {
        val tlsStore = TlsIdentityStore(File(temp.root, "tls2"), emptyList())
        assertTrue(tlsStore.signingKeys === tlsStore.signingKeys)
        val key = tlsStore.signingKeys.getOrCreate().public.encoded
        assertArrayEquals(key, TlsIdentityStore(File(temp.root, "tls2"), emptyList()).signingKeys.getOrCreate().public.encoded)
        assertTrue(File(temp.root, "tls2/signing_key.v1").isFile)
    }
}
