package com.fileapex.security.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class TlsIdentityStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val day = 86_400_000L

    private class AesVault(override val scheme: Int, private val key: javax.crypto.SecretKey) : TlsSecretVault {
        override fun seal(plain: ByteArray) = GcmBlob.sealWithRandomIv(key, plain)
        override fun open(sealed: ByteArray) = GcmBlob.open(key, sealed)
    }

    private class FailingVault(override val scheme: Int = 9) : TlsSecretVault {
        override fun seal(plain: ByteArray): ByteArray = error("boom")
        override fun open(sealed: ByteArray): ByteArray = error("boom")
    }

    private fun dir() = File(temp.root, "tls")

    @Test
    fun pinIsStableAcrossRestarts() {
        val first = TlsIdentityStore(dir(), emptyList()).getOrCreate()
        val second = TlsIdentityStore(dir(), emptyList()).getOrCreate()
        assertEquals(first.pin, second.pin)
        assertEquals(64, first.pin.length)
    }

    @Test
    fun wrappedIdentityRoundTripsAndFileIsNotPlain() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = AesVault(7, key)
        val created = TlsIdentityStore(dir(), listOf(vault)).getOrCreate()
        val raw = File(dir(), "tls_identity.v1").readBytes()
        assertEquals(7, raw[0].toInt())
        val pkcs8 = created.privateKey.encoded
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(pkcs8.copyOfRange(0, 16), Charsets.ISO_8859_1)))
        assertEquals(created.pin, TlsIdentityStore(dir(), listOf(vault)).getOrCreate().pin)
    }

    @Test
    fun failingVaultFallsBackToPlain() {
        val store = TlsIdentityStore(dir(), listOf(FailingVault()))
        val created = store.getOrCreate()
        assertEquals(0, File(dir(), "tls_identity.v1").readBytes()[0].toInt())
        assertEquals(created.pin, TlsIdentityStore(dir(), listOf(FailingVault())).getOrCreate().pin)
    }

    @Test
    fun unreadableIdentityIsNeverReplacedSilently() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TlsIdentityStore(dir(), listOf(AesVault(7, key))).getOrCreate()
        val before = File(dir(), "tls_identity.v1").readBytes()
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            TlsIdentityStore(dir(), listOf(AesVault(7, wrongKey))).getOrCreate()
            fail("expected TlsIdentityUnavailableException")
        } catch (_: TlsIdentityUnavailableException) {
        }
        assertTrue(before.contentEquals(File(dir(), "tls_identity.v1").readBytes()))
    }

    @Test
    fun missingVaultForStoredSchemeIsUnavailable() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TlsIdentityStore(dir(), listOf(AesVault(7, key))).getOrCreate()
        try {
            TlsIdentityStore(dir(), emptyList()).getOrCreate()
            fail("expected TlsIdentityUnavailableException")
        } catch (_: TlsIdentityUnavailableException) {
        }
    }

    @Test
    fun explicitRegenerateChangesPin() {
        val store = TlsIdentityStore(dir(), emptyList())
        val old = store.getOrCreate().pin
        val fresh = store.regenerate().pin
        assertNotEquals(old, fresh)
        assertEquals(fresh, TlsIdentityStore(dir(), emptyList()).getOrCreate().pin)
    }

    @Test
    fun reissueKeepsPinAndRefreshesValidity() {
        val now = System.currentTimeMillis()
        val old = TlsIdentityFactory.generate(now - 720 * day)
        assertTrue(TlsIdentityFactory.needsReissue(old, now))
        val renewed = TlsIdentityFactory.reissue(old, now)
        assertEquals(old.pin, renewed.pin)
        assertTrue(renewed.certificate.notAfter.time > old.certificate.notAfter.time)
        assertFalse(TlsIdentityFactory.needsReissue(renewed, now))
    }

    @Test
    fun storeReissuesExpiredCertificateOnTheSameKey() {
        val now = System.currentTimeMillis()
        val aged = TlsIdentityStore(dir(), emptyList(), clock = { now - 800 * day }).getOrCreate()
        val renewed = TlsIdentityStore(dir(), emptyList(), clock = { now }).getOrCreate()
        assertEquals(aged.pin, renewed.pin)
        assertTrue(renewed.certificate.notAfter.time > now)
    }

    @Test
    fun pinMatchingAndLogTruncation() {
        val pin = TlsIdentityFactory.generate().pin
        assertTrue(TlsPin.matches(pin, pin.uppercase()))
        assertFalse(TlsPin.matches(pin, TlsIdentityFactory.generate().pin))
        assertEquals(8, TlsPin.forLog(pin).length)
    }
}
