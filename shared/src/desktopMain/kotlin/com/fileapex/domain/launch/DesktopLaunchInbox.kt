package com.fileapex.domain.launch

import com.fileapex.domain.share.IncomingShareFile
import com.fileapex.domain.share.IncomingSharePayload
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class DesktopLaunchRequest(
    val id: String = UUID.randomUUID().toString(),
    val args: List<String> = emptyList(),
    val cwd: String,
    val activate: Boolean = true
)

data class DesktopLaunchInput(val id: String, val share: IncomingSharePayload?, val uris: List<String>, val activate: Boolean)

/** Launch requests remain pending until presentation consumes them, including during database bootstrap. */
class DesktopLaunchInbox {
    private val queued = MutableStateFlow<List<DesktopLaunchInput>>(emptyList())
    val pending = queued.asStateFlow()
    private val accepted = LinkedHashSet<String>()

    @Synchronized fun offer(request: DesktopLaunchRequest): Boolean {
        if (request.id.length !in 1..64 || request.cwd.length > 16_384 || !File(request.cwd).isAbsolute ||
            request.args.size > 1024 || request.args.any { it.length > 16_384 || '\u0000' in it }) return false
        if (request.id in accepted) return true
        if (queued.value.size >= 32) return false
        val uris = mutableListOf<String>()
        val files = mutableListOf<IncomingShareFile>()
        try {
            request.args.forEach { argument ->
                if (argument.startsWith("fileapex:", true) || argument.startsWith("apex:", true)) {
                    URI(argument)
                    uris += argument
                } else {
                    val path = if (argument.startsWith("file:", true)) File(URI(argument)) else File(argument).let {
                        if (it.isAbsolute) it else File(request.cwd, argument)
                    }
                    val absolute = path.toPath().toAbsolutePath().normalize().toFile()
                    files += IncomingShareFile(absolute.name, absolute.path, if (absolute.isFile) absolute.length() else 0L)
                }
            }
        } catch (_: IllegalArgumentException) { return false }
        catch (_: java.net.URISyntaxException) { return false }
        queued.value = queued.value + DesktopLaunchInput(request.id, files.takeIf { it.isNotEmpty() }?.let { IncomingSharePayload(request.id, it) }, uris.toList(), request.activate)
        accepted += request.id
        while (accepted.size > 512) accepted.remove(accepted.first())
        return true
    }

    @Synchronized fun consume(id: String) { queued.value = queued.value.filterNot { it.id == id } }

    companion object { val live = DesktopLaunchInbox() }
}
