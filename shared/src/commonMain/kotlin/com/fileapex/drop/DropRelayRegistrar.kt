package com.fileapex.drop

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tells the drop relay which endpoint ids may connect until [expiresAtMs]. True only when the relay's registry accepted them. */
fun interface DropRelayRegistrar {
    suspend fun register(ids: List<String>, expiresAtMs: Long): Boolean

    companion object {
        /** No registry: drops use the ordinary relay. */
        val None = DropRelayRegistrar { _, _ -> false }
    }
}

/** `POST /api/drop/register` on the FileApex site. No sign-in and nothing secret: only temporary endpoint ids and an expiry. */
class KtorDropRelayRegistrar(
    private val httpClient: () -> HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT
) : DropRelayRegistrar {
    override suspend fun register(ids: List<String>, expiresAtMs: Long): Boolean {
        val body = buildJsonObject {
            put("ids", JsonArray(ids.map(::JsonPrimitive)))
            put("expiresAtMs", expiresAtMs)
        }
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) {
                httpClient().post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
            response?.status?.isSuccess() == true
        } catch (_: java.io.IOException) {
            false
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://getfileapex.com/api/drop/register"
        private const val TIMEOUT_MS = 8_000L
    }
}
