package com.fileapex.cloud

import com.fileapex.di.FileApexServices
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal actual object FcmGoogleOAuth {
    private val cacheLock = Mutex()
    private var cachedToken: String? = null
    private var cachedExpiryEpochSec: Long = 0L

    actual suspend fun accessToken(config: FcmServiceAccountConfig): String? =
        withContext(Dispatchers.IO) {
            cacheLock.withLock {
                val nowSec = System.currentTimeMillis() / 1_000L
                if (!cachedToken.isNullOrBlank() && nowSec < cachedExpiryEpochSec - 60L) {
                    return@withLock cachedToken
                }
                val fetched = fetchAccessToken(config) ?: return@withLock null
                cachedToken = fetched.first
                cachedExpiryEpochSec = fetched.second
                cachedToken
            }
        }

    private suspend fun fetchAccessToken(config: FcmServiceAccountConfig): Pair<String, Long>? {
        return runCatching {
            val nowSec = System.currentTimeMillis() / 1_000L
            val jwt = buildSignedJwt(
                clientEmail = config.clientEmail,
                privateKeyPem = config.privateKeyPem,
                issuedAtSec = nowSec,
                expiresAtSec = nowSec + 3_600L
            )
            val response = FileApexServices.httpClient.post(GOOGLE_TOKEN_URI) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$jwt"
                )
            }
            if (!response.status.isSuccess()) return null
            val body = response.body<JsonObject>()
            val token = body["access_token"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (token.isEmpty()) return null
            val expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3_600L
            token to (nowSec + expiresIn)
        }.getOrNull()
    }

    private fun buildSignedJwt(
        clientEmail: String,
        privateKeyPem: String,
        issuedAtSec: Long,
        expiresAtSec: Long
    ): String {
        val header = base64UrlEncode("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = base64UrlEncode(
            (
                """{"iss":"$clientEmail","scope":"$FCM_SCOPE","aud":"$GOOGLE_TOKEN_URI",""" +
                    """"iat":$issuedAtSec,"exp":$expiresAtSec}"""
                ).toByteArray()
        )
        val signingInput = "$header.$payload"
        val signature = signRs256(signingInput.toByteArray(), parsePrivateKey(privateKeyPem))
        return "$signingInput.${base64UrlEncode(signature)}"
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(cleaned)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoded))
    }

    private fun signRs256(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private const val GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"
    private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
}
