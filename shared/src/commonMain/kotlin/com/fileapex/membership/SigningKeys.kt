package com.fileapex.membership

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Why a membership record was made. [USER_REMOVED] (Remove on a member) makes the named device let go of its cluster;
 * [LEFT_CLUSTER] is a device leaving on its own from Settings and wipes that device's own cluster.
 */
object MembershipReason {
    const val PAIRED = "paired"
    const val USER_REMOVED = "user_removed"
    const val LEFT_CLUSTER = "left_cluster"
    const val TLS_PIN = "tls_pin"
}

object MembershipKeys {
    fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun decode(spkiBase64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)))

    /** SHA-256 of the SubjectPublicKeyInfo as lowercase hex, the same value as the TLS pin of that key. */
    fun pin(spkiBase64: String): String =
        MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(spkiBase64)).joinToString("") { "%02x".format(it) }
}

/** Signs as one device. The app backs this with the device signing key ([com.fileapex.security.tls.SigningKeyStore]); tests use a generated key. */
interface EventSigner {
    val deviceId: String
    val publicKey: String
    fun sign(bytes: ByteArray): String
}

class EcEventSigner(
    override val deviceId: String,
    private val privateKey: PrivateKey,
    publicKey: PublicKey
) : EventSigner {
    override val publicKey: String = MembershipKeys.encode(publicKey)

    override fun sign(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance(ALGORITHM).run { initSign(privateKey); update(bytes); sign() })

    internal companion object {
        const val ALGORITHM = "SHA256withECDSA"
    }
}
