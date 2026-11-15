package com.fileapex.platform

import com.fileapex.cloud.diagnostics.DiagnosticsCrypto
import com.fileapex.domain.clipboard.ClipboardCrypto
import com.fileapex.drop.ed25519PublicKey
import com.fileapex.notice.NoticeVerifier
import com.fileapex.security.tls.TlsIdentityFactory
import java.security.SecureRandom

/** Run in its own JVM by AndroidBouncyCastleGuardTest: every BouncyCastle-backed flow the Android app uses. */
fun main() {
    val identity = TlsIdentityFactory.generate()
    val renewed = TlsIdentityFactory.reissue(identity, System.currentTimeMillis() + 86_400_000L)
    renewed.certificate.verify(renewed.certificate.publicKey)
    check(identity.pin == renewed.pin)

    val a = ClipboardCrypto.generateKeyPair()
    val b = ClipboardCrypto.generateKeyPair()
    val tagA = ClipboardCrypto.authenticate("hello".encodeToByteArray(), a.privateKey, b.publicKey, "salt")
    val tagB = ClipboardCrypto.authenticate("hello".encodeToByteArray(), b.privateKey, a.publicKey, "salt")
    check(tagA.contentEquals(tagB))
    check(ClipboardCrypto.publicKeyFromPrivate(a.privateKey).contentEquals(a.publicKey))

    check(DiagnosticsCrypto.generateKeyPair().publicKey.size == 32)

    val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val pub = ed25519PublicKey(seed)
    check(!NoticeVerifier.verify("m".encodeToByteArray(), ByteArray(64), listOf(pub)))
    println("FLOWS-OK")
}
