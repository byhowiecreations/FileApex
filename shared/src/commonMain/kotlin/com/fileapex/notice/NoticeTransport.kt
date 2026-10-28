package com.fileapex.notice

import io.ktor.http.Url

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

/** The Ktor transport with the offline error handling of the JVM targets. */
expect fun createNoticeTransport(policy: NoticeUrlPolicy): NoticeTransport
