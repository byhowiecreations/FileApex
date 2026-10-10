package com.fileapex.security.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** This device's TLS key and self-signed certificate. Peers pin [pin], not the certificate. */
class TlsIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val pin: String get() = TlsPin.of(certificate.publicKey)
}

object TlsPin {
    /** SHA-256 of the SubjectPublicKeyInfo, lowercase hex. */
    fun of(publicKey: PublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).joinToString("") { "%02x".format(it) }

    fun of(certificate: X509Certificate): String = of(certificate.publicKey)

    /** Pins are only ever logged truncated. */
    fun forLog(pin: String): String = pin.take(8)

    fun matches(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())
}

object TlsIdentityFactory {
    private const val VALIDITY_DAYS = 730L
    private const val BACKDATE_DAYS = 1L
    const val RENEW_BEFORE_DAYS = 30L
    private const val DAY_MS = 86_400_000L

    fun generate(now: Long = System.currentTimeMillis()): TlsIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = generator.generateKeyPair()
        return TlsIdentity(pair.private, issue(pair, now))
    }

    /** New certificate on the same key, so the pin is unchanged. */
    fun reissue(identity: TlsIdentity, now: Long = System.currentTimeMillis()): TlsIdentity =
        TlsIdentity(
            identity.privateKey,
            issue(KeyPair(identity.certificate.publicKey, identity.privateKey), now)
        )

    fun needsReissue(identity: TlsIdentity, now: Long = System.currentTimeMillis()): Boolean =
        identity.certificate.notAfter.time - now < RENEW_BEFORE_DAYS * DAY_MS

    // Lightweight signer path: no provider name, so Android's stripped platform BC is never looked up.
    private fun issue(pair: KeyPair, now: Long): X509Certificate {
        val name = X500Name("CN=FileApex")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - BACKDATE_DAYS * DAY_MS),
            Date(now + VALIDITY_DAYS * DAY_MS),
            name,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
