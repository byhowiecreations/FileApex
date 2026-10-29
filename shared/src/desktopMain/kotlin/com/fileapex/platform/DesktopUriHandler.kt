package com.fileapex.platform

import java.awt.Desktop
import java.net.URI

/** Receives `fileapex://` links the system hands to the running app: the Google sign-in return. */
object DesktopUriHandler {
    fun installOpenUriHandler() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return
            desktop.setOpenURIHandler { event -> handleOAuthCallback(event.uri) }
            println("DesktopUriHandler: APP_OPEN_URI handler installed")
        }.onFailure { error ->
            println("DesktopUriHandler: URI handler not available :: ${error.message}")
        }
    }

    private fun handleOAuthCallback(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "apex" && scheme != "fileapex") return false
        if (uri.host != "oauth-callback") return false
        val params = parseQuery(uri)
        DesktopOAuthCallbacks.emit(OAuthCodeResult(code = params["code"], state = params["state"], error = params["error"]))
        println("DesktopUriHandler: OAuth callback received")
        return true
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val query = uri.rawQuery ?: uri.query ?: return emptyMap()
        return query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = java.net.URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8)
            val value = java.net.URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8)
            key to value
        }.toMap()
    }
}
