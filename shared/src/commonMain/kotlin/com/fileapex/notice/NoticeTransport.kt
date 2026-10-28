package com.fileapex.notice

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

sealed interface NoticeHttpResponse {
    data object NotModified : NoticeHttpResponse
    class Ok(val body: ByteArray, val etag: String?) : NoticeHttpResponse
    data object Failed : NoticeHttpResponse
}

fun interface NoticeTransport {
    suspend fun get(url: String, etag: String?): NoticeHttpResponse
}

/** Where a notice may be fetched from: https only, one host, and redirects must stay on it. */
class NoticeUrlPolicy(val host: String, private val httpsOnly: Boolean = true) {
    fun allows(raw: String): Boolean {
        val url = try {
            Url(raw)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val schemeOk = url.protocol.name == "https" || (!httpsOnly && url.protocol.name == "http")
        return schemeOk && url.host.equals(host, ignoreCase = true) && url.user == null && url.password == null
    }

    companion object {
        fun forUrl(url: String): NoticeUrlPolicy = NoticeUrlPolicy(Url(url).host)
    }
}

/**
 * The only request headers are `If-None-Match` and the client's own defaults: no id, version, device or account, and no
 * custom User-Agent.
 */
class KtorNoticeTransport(
    private val policy: NoticeUrlPolicy,
    private val client: HttpClient = defaultClient()
) : NoticeTransport {
    override suspend fun get(url: String, etag: String?): NoticeHttpResponse {
        var target = url
        try {
            repeat(MAX_REDIRECTS + 1) {
                if (!policy.allows(target)) return NoticeHttpResponse.Failed
                val response = client.get(target) { if (etag != null) header(HttpHeaders.IfNoneMatch, etag) }
                when (response.status) {
                    HttpStatusCode.NotModified -> return NoticeHttpResponse.NotModified
                    HttpStatusCode.OK -> return readBody(response)
                    HttpStatusCode.MovedPermanently, HttpStatusCode.Found, HttpStatusCode.TemporaryRedirect,
                    HttpStatusCode.PermanentRedirect -> target = response.headers[HttpHeaders.Location]
                        ?.let { resolve(target, it) } ?: return NoticeHttpResponse.Failed
                    else -> return NoticeHttpResponse.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("NoticeTransport: request failed (${e::class.simpleName})")
            return NoticeHttpResponse.Failed
        }
        return NoticeHttpResponse.Failed
    }

    private suspend fun readBody(response: HttpResponse): NoticeHttpResponse {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > NoticeEnvelopeParser.MAX_FILE_BYTES) return NoticeHttpResponse.Failed
        val bytes = response.bodyAsChannel().readRemaining(NoticeEnvelopeParser.MAX_FILE_BYTES + 1L).readByteArray()
        if (bytes.size > NoticeEnvelopeParser.MAX_FILE_BYTES) return NoticeHttpResponse.Failed
        return NoticeHttpResponse.Ok(bytes, response.headers[HttpHeaders.ETag])
    }

    private fun resolve(base: String, location: String): String? =
        try {
            URLBuilder(base).takeFrom(location).buildString()
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        const val MAX_REDIRECTS = 2

        fun defaultClient() = HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }
    }
}
