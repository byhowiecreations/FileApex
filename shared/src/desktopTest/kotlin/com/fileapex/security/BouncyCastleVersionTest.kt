package com.fileapex.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 22 advisories listed for 1.78.1 are fixed in 1.85 and 1.86, so neither BouncyCastle artifact may fall back below 1.87. */
class BouncyCastleVersionTest {
    private fun jarOf(type: Class<*>): String = type.protectionDomain.codeSource.location.path.substringAfterLast('/')

    @Test
    fun theProviderJarIsAtLeast187() {
        val jar = jarOf(BouncyCastleProvider::class.java)
        assertTrue("bcprov is $jar", jar.startsWith("bcprov-jdk18on-1.87"))
    }

    @Test
    fun theCertificateJarIsAtLeast187() {
        val jar = jarOf(org.bouncycastle.cert.X509v3CertificateBuilder::class.java)
        assertTrue("bcpkix is $jar", jar.startsWith("bcpkix-jdk18on-1.87"))
    }
}
