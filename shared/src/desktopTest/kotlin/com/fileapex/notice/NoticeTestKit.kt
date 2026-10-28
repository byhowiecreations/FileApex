package com.fileapex.notice

import com.fileapex.update.FileApexSemVer
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/** Throwaway key pair made in memory for each test run; no key is stored anywhere. */
class NoticeTestKey {
    private val pair = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val privateKey = pair.private as Ed25519PrivateKeyParameters
    val publicKey: ByteArray = (pair.public as Ed25519PublicKeyParameters).encoded

    fun sign(payload: String): ByteArray {
        val bytes = payload.encodeToByteArray()
        return Ed25519Signer().run {
            init(true, privateKey)
            update(bytes, 0, bytes.size)
            generateSignature()
        }
    }

    fun file(payload: String): ByteArray = envelope(payload, sign(payload))
}

fun envelope(payload: String, signature: ByteArray): ByteArray {
    val quoted = kotlinx.serialization.json.JsonPrimitive(payload).toString()
    return """{"payload":$quoted,"sig":"${Base64.getEncoder().encodeToString(signature)}"}""".encodeToByteArray()
}

object NoticeSamples {
    const val ISSUED = "2026-10-28"
    val nowMs: Long = NoticeDate.parseEpochDay(ISSUED)!! * NoticeDate.MS_PER_DAY + 3_600_000L

    fun notice(
        id: String = "n1",
        severity: String = "info",
        extra: String = "",
        expires: String = "2027-03-01"
    ) = """{"id":"$id","severity":"$severity","expires":"$expires","title":{"en":"Title"},"text":{"en":"Body"}$extra}"""

    fun payload(serial: Long = 1, issued: String = ISSUED, vararg notices: String, v: Int = 1) =
        """{"v":$v,"serial":$serial,"issued":"$issued","notices":[${notices.joinToString(",")}]}"""

    fun context(
        version: String = "0.19.9",
        platform: NoticePlatform = NoticePlatform.Macos,
        channel: NoticeChannel = NoticeChannel.Github,
        features: Set<NoticeFeature> = emptySet(),
        nowEpochMs: Long = nowMs
    ) = NoticeContext(FileApexSemVer.parse(version), platform, channel, features, nowEpochMs)

    fun parsed(payload: String): NoticePayload =
        (NoticePayloadParser.parse(payload) as NoticePayloadResult.Ok).payload
}
