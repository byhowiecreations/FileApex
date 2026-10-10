package com.fileapex.security.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MacKeychainTlsVaultTest {
    @Test
    fun sealAndOpenRoundTripThroughRealKeychain() {
        assumeTrue(System.getProperty("os.name").contains("Mac"))
        val service = "com.fileapex.tls.test.${System.nanoTime()}"
        try {
            val vault = MacKeychainTlsVault(service)
            val data = ByteArray(300) { it.toByte() }
            val sealed = vault.seal(data)
            assertArrayEquals(data, MacKeychainTlsVault(service).open(sealed))
            val dir = File(System.getProperty("java.io.tmpdir"), "tls-kc-${System.nanoTime()}")
            val created = TlsIdentityStore(dir, listOf(MacKeychainTlsVault(service))).getOrCreate()
            assertEquals(created.pin, TlsIdentityStore(dir, listOf(MacKeychainTlsVault(service))).getOrCreate().pin)
            assertEquals(2, File(dir, "tls_identity.v1").readBytes()[0].toInt())
            dir.deleteRecursively()
        } finally {
            ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", service, "-a", "wrap-key")
                .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        }
    }
}
