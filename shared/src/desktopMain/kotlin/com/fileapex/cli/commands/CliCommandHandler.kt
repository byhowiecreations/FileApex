package com.fileapex.cli.commands

import com.fileapex.cli.CliDeviceAliasManager
import com.fileapex.cli.CliDeviceResolver
import com.fileapex.cli.CliTokenizer
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.platform.PlatformClipboard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class CliCommandHandler(
    private val out: (String) -> Unit = { println(it) },
    private val err: (String) -> Unit = { System.err.println(it) },
    private val onProgress: (percent: Float, speed: String, eta: String, sentBytes: Long, totalBytes: Long) -> Unit = { _, _, _, _, _ -> },
    private val prompt: (String) -> String? = { q -> print(q); System.out.flush(); kotlin.io.readLine() }
) {

    suspend fun execute(rawArgs: List<String>, cwd: String): Int {
        val tokens = rawArgs.map { CliTokenizer.stripQuotes(it) }.filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens[0] == "help" || tokens[0] == "-h" || tokens[0] == "--help") {
            printHelp()
            return 0
        }

        return when (val cmd = tokens[0].lowercase()) {
            "dash", "dashboard" -> {
                val backend = com.fileapex.cli.dash.StandaloneClusterBackend()
                com.fileapex.cli.dash.CliDashboard(backend, cwd).run()
                0
            }
            "devices" -> handleDevices()
            "alias" -> handleAlias(tokens.drop(1))
            "send" -> handleSend(tokens.drop(1), cwd)
            "clip" -> handleClip(tokens.drop(1))
            "queue" -> handleQueue(tokens.drop(1))
            else -> {
                err("Unknown command: $cmd")
                printHelp()
                1
            }
        }
    }

    private fun printHelp() {
        out(
            """
            |FileApex CLI - Local-First P2P File Explorer & Transfer
            |
            |Usage:
            |  fileapex <command> [options] [arguments]
            |
            |Commands:
            |  help, -h
            |      Display this overview of all available commands and options.
            |
            |  dash, dashboard
            |      Launch interactive Terminal User Interface (TUI) dashboard to monitor cluster,
            |      manage peers, track streaming transfers, browse remote files, and control queue.
            |
            |  devices
            |      Output formatted table of authenticated paired devices, including
            |      generated slugs/aliases, online status, and battery percentage.
            |
            |  alias [full_name] [short_slug]
            |      Map a user-defined short slug to a paired device.
            |
            |  send <file path> [alias] OR send [alias] <file path>
            |      Send a file to destination, or if you use 'bb' for [alias] it will go to the Bulletin Board on all devices. The app will understand it in any order the same way (send local to destination).
            |
            |  send "message in quotes" [alias] OR send [alias] "message in quotes"
            |      Send a message to the destination device, or to 'bb' for Bulletin Board.
            |
            |  send clip [alias] OR send [alias] clip
            |      Send current local clipboard content to destination device.
            |
            |  send beep [alias] OR send [alias] beep
            |      FileApex will send an audible 'beep' to the destination if you need to locate it.
            |
            |  clip [device/alias]
            |      Interactive prompt to send or retrieve clipboard with target device.
            |
            |  clip send [device/alias]
            |      Sends current clipboard content to destination device.
            |
            |  clip push [device/alias]
            |      Does the same thing.
            |
            |  clip pull [device/alias]
            |      Retrieves remote clipboard content from destination device (if Android, keep FileApex in foreground).
            |
            |  clip rec [device/alias]
            |      Does the same thing.
            |
            |  clip receive [device/alias]
            |      Does the same thing.
            |
            |  clip retrieve [device/alias]
            |      Does the same thing.
            |
            |  queue
            |      Output formatted list of all pending transfers in the queue.
            |
            |  queue remove <index> or <range> (e.g. 2-4)
            |      Remove single transfer by index or a range of items from the queue.
            |
            |  queue rem <index> or <range> (e.g. 2-4)
            |      Does the same thing.
            """.trimMargin()
        )
    }

    private suspend fun handleDevices(): Int {
        val repo = FileApexServices.deviceRepository
        val devices = CliDeviceResolver.getAuthenticatedDevices(repo)
        if (devices.isEmpty()) {
            out("No known device")
            return 0
        }

        out(String.format("%-28s %-20s %-12s %-10s", "DEVICE NAME", "SLUG / ALIAS", "STATUS", "BATTERY"))
        out("-".repeat(74))

        for (dev in devices) {
            val name = dev.deviceName.ifBlank { "Unknown" }
            val slug = CliDeviceAliasManager.resolveEffectiveSlug(dev)

            // Probe device reachability and battery
            var online = false
            var batteryStr = "--"

            val direct = dev.lastKnownIp.takeIf { it.isNotBlank() }
            if (direct != null && dev.port > 0) {
                val ping = withTimeoutOrNull(800) {
                    runCatching {
                        FileApexServices.client.fetchPeerNodeState(direct, dev.port, 800)
                    }.getOrNull()
                }
                if (ping != null) {
                    online = true
                    val bat = withTimeoutOrNull(800) {
                        runCatching {
                            FileApexServices.client.fetchFastBattery(direct, dev.port)
                        }.getOrNull()
                    }
                    if (bat?.levelPercent != null && bat.levelPercent > 0) {
                        batteryStr = "${bat.levelPercent}%"
                    }
                }
            }

            val statusStr = if (online) "Online" else "Offline"
            out(String.format("%-28s %-20s %-12s %-10s", name.take(27), slug.take(19), statusStr, batteryStr))
        }
        return 0
    }

    private suspend fun handleAlias(args: List<String>): Int {
        if (args.size < 2) {
            err("Usage: fileapex alias [full_name] [short_slug]")
            return 1
        }
        val targetQuery = args[0]
        val shortSlug = args[1].trim().lowercase()

        val repo = FileApexServices.deviceRepository
        val device = CliDeviceResolver.resolveDevice(
            query = targetQuery,
            repository = repo,
            readLine = { prompt("") }
        )

        if (device == null) {
            out("No known device")
            return 1
        }

        CliDeviceAliasManager.setAlias(device.deviceId, shortSlug)
        out("Alias '$shortSlug' successfully set for '${device.deviceName}'.")
        return 0
    }

    private suspend fun handleSend(args: List<String>, cwd: String): Int {
        if (args.size < 2) {
            err("Usage: fileapex send [alias] <path_or_msg> OR fileapex send <path_or_msg> [alias]")
            return 1
        }

        val arg1 = args[0]
        val arg2 = args.drop(1).joinToString(" ")

        // Resolve bidirectional ordering
        val (targetQuery, payload) = resolveSendArgs(arg1, arg2, cwd)

        // Case 1: Target is Bulletin Board ("bb")
        if (targetQuery.equals("bb", ignoreCase = true)) {
            out("Routing note to Bulletin Board...")
            FileApexServices.noteRepository.sendNote(content = payload)
            out("Note successfully posted to Bulletin Board.")
            return 0
        }

        // Case 2: Resolve paired target device
        val repo = FileApexServices.deviceRepository
        val device = CliDeviceResolver.resolveDevice(
            query = targetQuery,
            repository = repo,
            readLine = { prompt("") }
        )

        if (device == null) {
            out("No known device")
            return 1
        }

        // Check if payload is a valid local file path
        val candidateFile = File(payload).let { if (it.isAbsolute) it else File(cwd, payload) }

        if (candidateFile.exists()) {
            return streamLocalFile(candidateFile, device)
        }

        // Check if payload is 'clip' or 'clipboard' -> push clipboard content
        if (payload.equals("clip", ignoreCase = true) || payload.equals("clipboard", ignoreCase = true)) {
            return sendClipboardToDevice(device)
        }

        // Check if payload is 'beep'
        if (payload.equals("beep", ignoreCase = true)) {
            return triggerDeviceBeep(device)
        }

        // Otherwise: treat payload as high-priority push notification
        return sendPushNotification(device, payload)
    }

    internal suspend fun resolveSendArgs(arg1: String, arg2: String, cwd: String): Pair<String, String> {
        if (arg1.equals("bb", ignoreCase = true)) return Pair("bb", arg2)
        if (arg2.equals("bb", ignoreCase = true)) return Pair("bb", arg1)

        val isClip1 = arg1.equals("clip", ignoreCase = true) || arg1.equals("clipboard", ignoreCase = true)
        val isClip2 = arg2.equals("clip", ignoreCase = true) || arg2.equals("clipboard", ignoreCase = true)
        if (isClip1 && !isClip2) return Pair(arg2, "clip")
        if (isClip2 && !isClip1) return Pair(arg1, "clip")

        val file1 = File(arg1).let { if (it.isAbsolute) it else File(cwd, arg1) }
        val file2 = File(arg2).let { if (it.isAbsolute) it else File(cwd, arg2) }

        if (file1.exists() && !file2.exists()) return Pair(arg2, arg1)
        if (file2.exists() && !file1.exists()) return Pair(arg1, arg2)

        if (arg1.equals("beep", ignoreCase = true)) return Pair(arg2, "beep")
        if (arg2.equals("beep", ignoreCase = true)) return Pair(arg1, "beep")

        val repo = FileApexServices.deviceRepository
        val dev1 = CliDeviceResolver.resolveDevice(arg1, repo)
        val dev2 = CliDeviceResolver.resolveDevice(arg2, repo)

        if (dev1 != null && dev2 == null) return Pair(arg1, arg2)
        if (dev2 != null && dev1 == null) return Pair(arg2, arg1)

        // Default order: arg1 is target, arg2 is payload
        return Pair(arg1, arg2)
    }

    private suspend fun streamLocalFile(file: File, device: PairedDeviceEntity): Int {
        out("Preparing transfer: ${file.name} -> ${device.deviceName}")
        val transferQueue = FileApexServices.transferQueue

        // Observe progress in background coroutine with 100ms throttling to avoid socket buffering lag
        var lastEmittedMs = 0L
        val progressJob = CoroutineScope(Dispatchers.Default).launch {
            TransferActivityGuard.statsFlow.collect { stats ->
                val now = System.currentTimeMillis()
                if (stats.isActive && (now - lastEmittedMs >= 100L || stats.progress >= 1f)) {
                    lastEmittedMs = now
                    onProgress(
                        stats.progress,
                        stats.speedFormatted,
                        stats.etaFormatted,
                        stats.sentBytes,
                        stats.totalBytes
                    )
                }
            }
        }

        return try {
            val result = transferQueue.sendLocalPathsOrQueue(
                absolutePaths = listOf(file.absolutePath),
                deviceIds = listOf(device.deviceId)
            )

            progressJob.cancel()
            if (result.hadImmediateSend) {
                onProgress(1f, "", "", file.length(), file.length())
                out("\nTransfer complete: ${file.name} sent to ${device.deviceName}.")
                0
            } else if (result.hadQueue) {
                out("\nTarget device is currently offline. Transfer staged into persistent queue.")
                0
            } else {
                err("\nSend failed: ${result.message}")
                1
            }
        } catch (e: Throwable) {
            progressJob.cancel()
            err("\nTransfer encountered an error: ${e.message}")
            out("Staging transfer to queue for offline delivery...")
            transferQueue.enqueueLocalPaths(listOf(file.absolutePath), listOf(device.deviceId))
            0
        }
    }

    private suspend fun triggerDeviceBeep(device: PairedDeviceEntity): Int {
        out("Triggering audible locator sound on ${device.deviceName}...")
        val direct = device.lastKnownIp.takeIf { it.isNotBlank() }
        if (direct != null && device.port > 0) {
            val success = runCatching {
                FileApexServices.client.triggerDeviceBeep(direct, device.port)
            }.getOrDefault(false)

            if (success) {
                out("Audible locator triggered on ${device.deviceName}.")
                return 0
            }
        }
        out("Target device is unreachable on LAN.")
        return 1
    }

    private suspend fun sendPushNotification(device: PairedDeviceEntity, message: String): Int {
        out("Sending high-priority notification to ${device.deviceName}...")
        val direct = device.lastKnownIp.takeIf { it.isNotBlank() }
        val selfName = loadLocalIdentity().deviceName

        if (direct != null && device.port > 0) {
            val sent = runCatching {
                FileApexServices.client.sendDirectAlert(direct, device.port, selfName, message)
            }.getOrDefault(false)

            if (sent) {
                out("Notification delivered to ${device.deviceName}.")
                return 0
            }
        }

        // Offline staging: route as persistent Bulletin Board note so it syncs when device connects
        out("Target device is offline. Staging notification into persistent queue...")
        FileApexServices.noteRepository.sendNote(content = "[$selfName -> ${device.deviceName}] $message")
        out("Message staged.")
        return 0
    }

    private suspend fun handleClip(args: List<String>): Int {
        if (args.isEmpty()) {
            err("Usage: fileapex clip [device/alias] OR fileapex clip send/pull [device/alias]")
            return 1
        }

        var action: String? = null
        var targetQuery: String? = null

        val first = args[0].lowercase()
        if (first in listOf("send", "push", "pull", "rec", "receive", "retrieve")) {
            action = first
            if (args.size > 1) targetQuery = args[1]
        } else {
            targetQuery = args[0]
            if (args.size > 1) action = args[1].lowercase()
        }

        val repo = FileApexServices.deviceRepository
        val device = if (!targetQuery.isNullOrBlank()) {
            CliDeviceResolver.resolveDevice(
                query = targetQuery,
                repository = repo,
                readLine = { prompt("") }
            )
        } else null

        if (device == null) {
            out("No known device")
            return 1
        }

        // Interactive Prompt if no action flag provided
        val resolvedAction = if (action == null) {
            val response = prompt("Send current clipboard or retrieve from ${device.deviceName}? (send/retrieve): ")
                ?.trim()?.lowercase() ?: return 1
            when {
                response.startsWith("s") || response == "push" -> "send"
                response.startsWith("r") || response.startsWith("p") || response == "rec" -> "pull"
                else -> {
                    err("Invalid action. Expected 'send' or 'retrieve'.")
                    return 1
                }
            }
        } else {
            when (action) {
                "send", "push" -> "send"
                "pull", "rec", "receive", "retrieve" -> "pull"
                else -> action
            }
        }

        return when (resolvedAction) {
            "send" -> sendClipboardToDevice(device)
            "pull" -> {
                out("Retrieving remote clipboard from ${device.deviceName}...")
                out("Note: If retrieving from an Android device, ensure FileApex is open in the foreground so Android permits system clipboard access.")
                val direct = device.lastKnownIp.takeIf { it.isNotBlank() }
                if (direct == null || device.port <= 0) {
                    err("Cannot reach ${device.deviceName}: offline or missing IP.")
                    return 1
                }

                runCatching {
                    val content = FileApexServices.client.pullRemoteClipboard(direct, device.port)
                    if (content != null) {
                        PlatformClipboard.setSystemClipboardText(content)
                        out("Retrieved clipboard content from ${device.deviceName} (${content.length} chars).")
                        0
                    } else {
                        err("Remote clipboard was empty.")
                        1
                    }
                }.getOrElse { e ->
                    err("Failed to retrieve clipboard: ${e.message}")
                    1
                }
            }
            else -> {
                err("Unknown clipboard action: $resolvedAction")
                1
            }
        }
    }

    private suspend fun sendClipboardToDevice(device: PairedDeviceEntity): Int {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) {
            val answer = prompt("Local clipboard sharing is currently disabled. Enable it now? (y/n): ")
                ?.trim()?.lowercase()
            if (answer == "y" || answer == "yes") {
                settings.setClipboardSharingEnabled(true)
            } else {
                err("Clipboard sharing cancelled: local sharing is disabled.")
                return 1
            }
        }

        out("Pushing local clipboard to ${device.deviceName}...")
        return runCatching {
            ClipboardShareCoordinator.sendToDevice(device.deviceId)
            out("Clipboard content successfully sent to ${device.deviceName}.")
            0
        }.getOrElse { e ->
            err("Failed to send clipboard: ${e.message}")
            1
        }
    }

    private suspend fun handleQueue(args: List<String>): Int {
        val transferQueue = FileApexServices.transferQueue
        val items = transferQueue.pendingItems.first()

        if (args.isEmpty()) {
            if (items.isEmpty()) {
                out("Transfer queue is empty.")
                return 0
            }
            out("Pending Transfers (${items.size}):")
            items.forEachIndexed { idx, item ->
                val target = item.pendingDeviceNames.joinToString(", ").ifBlank { "Unknown" }
                val error = item.lastError?.let { " ($it)" }.orEmpty()
                out("  [${idx + 1}] ${item.sourceSummary} -> $target$error")
            }
            return 0
        }

        val subCmd = args[0].lowercase()
        if (subCmd == "remove" || subCmd == "rem") {
            if (args.size < 2) {
                err("Usage: fileapex queue remove <index> or <range> (e.g. 2-4) OR fileapex queue rem <index> or <range> (e.g. 2-4)")
                return 1
            }
            val spec = args[1].trim()
            val indicesToRemove = parseIndexRange(spec, items.size)
            if (indicesToRemove.isEmpty()) {
                err("Invalid queue index or range: '$spec'. Valid range is 1 to ${items.size}.")
                return 1
            }

            for (i in indicesToRemove) {
                val item = items[i - 1]
                transferQueue.remove(item.id)
                out("Removed [${i}] ${item.sourceSummary}")
            }
            return 0
        }

        err("Unknown queue action: $subCmd")
        return 1
    }

    companion object {
        fun parseIndexRange(spec: String, maxIndex: Int): List<Int> {
            if (spec.contains('-')) {
                val parts = spec.split('-')
                if (parts.size == 2) {
                    val start = parts[0].toIntOrNull() ?: return emptyList()
                    val end = parts[1].toIntOrNull() ?: return emptyList()
                    if (start in 1..maxIndex && end in 1..maxIndex && start <= end) {
                        return (start..end).toList()
                    }
                }
                return emptyList()
            }
            val single = spec.toIntOrNull() ?: return emptyList()
            return if (single in 1..maxIndex) listOf(single) else emptyList()
        }
    }
}
