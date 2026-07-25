package com.fileapex.cloud

import com.fileapex.platform.OAuthCodeResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Desktop OAuth loopback on a fixed port so the Web OAuth client can register an exact redirect URI.
 * Uses Ktor CIO (shipped in FileApex.app) — not jdk.httpserver, which jpackage omits by default.
 */
internal object DesktopOAuthLoopbackServer {
    private var engine: EmbeddedServer<*, *>? = null

    fun start(onCallback: (OAuthCodeResult) -> Unit): String {
        stop()
        val redirectUri = DESKTOP_OAUTH_LOOPBACK_REDIRECT_URI
        val port = DESKTOP_OAUTH_LOOPBACK_PORT
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/callback") {
                    val params = call.request.queryParameters
                    onCallback(
                        OAuthCodeResult(
                            code = params["code"],
                            state = params["state"],
                            error = params["error"]
                        )
                    )
                    call.respondText(SUCCESS_HTML, ContentType.Text.Html, HttpStatusCode.OK)
                    stop()
                }
            }
        }
        runCatching {
            server.start(wait = false)
        }.getOrElse { error ->
            throw IllegalStateException(
                "OAuth loopback could not bind 127.0.0.1:$port — close any app using that port and retry",
                error
            )
        }
        engine = server
        return redirectUri
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        engine = null
    }

    private const val SUCCESS_HTML =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>FileApex</title></head>" +
            "<body style=\"font-family:system-ui;text-align:center;margin-top:3rem\">" +
            "<h2>Signed in</h2><p>You can close this tab and return to FileApex.</p></body></html>"
}
