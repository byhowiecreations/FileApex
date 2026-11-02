package com.fileapex.platform

import java.awt.Desktop
import java.net.URI
import com.fileapex.domain.launch.DesktopLaunchInbox
import com.fileapex.domain.launch.DesktopLaunchRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Native launch events enter the same pending domain inbox as forwarded launcher arguments. */
object DesktopUriHandler {
    private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized private fun active() = scope

    /** App shutdown: pending file events are cancelled; a later event gets a fresh scope. */
    @Synchronized fun stop() {
        scope.cancel()
        scope = newScope()
    }

    fun installOpenUriHandler() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { event -> queueUri(event.uri) }
            if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) desktop.setOpenFileHandler { event -> queueFiles(event.files) }
        }.onFailure { error ->
            println("DesktopUriHandler: URI handler not available :: ${error.message}")
        }
    }

    fun queueUri(uri: URI, inbox: DesktopLaunchInbox = DesktopLaunchInbox.live): Boolean =
        inbox.offer(DesktopLaunchRequest(args = listOf(uri.toString()), cwd = System.getProperty("user.dir")))

    fun queueFiles(files: List<File>, inbox: DesktopLaunchInbox = DesktopLaunchInbox.live) = active().launch {
        com.fileapex.util.cancellableCatching {
            val args = files.map { it.absolutePath }
            ensureActive()
            if (!inbox.offer(DesktopLaunchRequest(args = args, cwd = System.getProperty("user.dir"))))
                println("DesktopUriHandler: file launch was refused")
        }.onFailure { println("DesktopUriHandler: file launch failed :: ${it::class.simpleName}") }
    }

    fun handleOAuthCallback(uri: URI): Boolean {
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
