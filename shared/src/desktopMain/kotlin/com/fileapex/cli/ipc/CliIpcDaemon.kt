package com.fileapex.cli.ipc

import com.fileapex.cli.dash.CliClusterEngine
import com.fileapex.cli.commands.CliCommandHandler
import com.fileapex.util.cancellableCatching
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import com.fileapex.platform.DesktopInstanceWire
import com.fileapex.platform.DesktopPlatformPaths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CliIpcDaemon {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(8)
    private const val REQUEST_TIMEOUT_MS = 10_000
    private const val PROMPT_TIMEOUT_MS = 10 * 60_000

    @Synchronized fun start(port: Int = CLI_IPC_PORT, credential: ByteArray? = null, requestTimeoutMs: Int = REQUEST_TIMEOUT_MS): Int? {
        serverSocket?.let { return it.localPort }
        return cancellableCatching {
            val key = credential?.copyOf() ?: DesktopInstanceWire.readDescriptor(DesktopPlatformPaths.applicationSupportDirectory()).key()
            val listener = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
            serverSocket = listener
            scope.launch {
                try {
                    while (!listener.isClosed) {
                        slots.acquire()
                        var assigned = false
                        try {
                            val client = listener.accept()
                            clients.add(client)
                            scope.launch {
                                try { handleClient(client, key, requestTimeoutMs) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { println("CliIpcDaemon: client ended :: ${e::class.simpleName}"); client.close() }
                                finally { clients.remove(client) }
                            }.invokeOnCompletion { slots.release() }
                            assigned = true
                        } finally { if (!assigned) slots.release() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: IOException) { if (!listener.isClosed) println("CliIpcDaemon: listener ended :: ${e::class.simpleName}") }
                finally { key.fill(0) }
            }
            listener.localPort
        }.onFailure { error ->
            println("CliIpcDaemon: Could not start :: ${error::class.simpleName}")
        }.getOrNull()
    }

    fun stop() {
        runCatching {
            serverSocket?.close()
            serverSocket = null
            clients.forEach { it.close() }
        }
    }

    private suspend fun handleClient(socket: Socket, key: ByteArray, requestTimeoutMs: Int) {
        socket.use { s ->
            s.soTimeout = 1500
            if (!DesktopInstanceWire.deadline(s) { DesktopInstanceWire.serverAuthenticate(s, key, "cli") }) return
            val reader = java.io.BufferedInputStream(s.getInputStream())
            val writer = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)

            // The request must arrive promptly; once a command runs the socket may stay quiet for as long as it takes.
            s.soTimeout = requestTimeoutMs
            val firstLine = reader.readBoundedLine() ?: return
            s.soTimeout = 0
            // Check if this is a structured CLI request or legacy path handoff
            val request = runCatching {
                json.decodeFromString<CliRequest>(firstLine)
            }.getOrNull()

            if (request != null) {
                if (request.requestType != "COMMAND") {
                    handleDashboardRequest(request, writer)
                    return
                }

                val handler = CliCommandHandler(
                    out = { text ->
                        val packet = CliIpcPacket.Stdout(text)
                        writer.println(json.encodeToString<CliIpcPacket>(packet))
                    },
                    err = { text ->
                        val packet = CliIpcPacket.Stderr(text)
                        writer.println(json.encodeToString<CliIpcPacket>(packet))
                    },
                    onProgress = { percent, speed, eta, sent, total ->
                        val packet = CliIpcPacket.Progress(percent, speed, eta, sent, total)
                        writer.println(json.encodeToString<CliIpcPacket>(packet))
                    },
                    prompt = { question ->
                        val packet = CliIpcPacket.Prompt(question)
                        writer.println(json.encodeToString<CliIpcPacket>(packet))
                        s.soTimeout = PROMPT_TIMEOUT_MS
                        val responseLine = try { reader.readBoundedLine() } catch (_: java.net.SocketTimeoutException) { null } finally { s.soTimeout = 0 }
                        if (responseLine != null) {
                            runCatching {
                                val inputPacket = json.decodeFromString<CliIpcPacket>(responseLine)
                                (inputPacket as? CliIpcPacket.Input)?.text
                            }.getOrNull() ?: responseLine
                        } else {
                            null
                        }
                    }
                )

                val exitCode = handler.execute(request.args, request.cwd)
                val exitPacket = CliIpcPacket.Exit(exitCode)
                writer.println(json.encodeToString<CliIpcPacket>(exitPacket))
            }
        }
    }

    private suspend fun handleDashboardRequest(request: CliRequest, writer: PrintWriter) {
        when (request.requestType) {
            "DASH_STATE" -> {
                val parts = request.payload.split(",")
                val highlight = parts.getOrNull(0)?.toBooleanStrictOrNull() ?: false
                val force = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                val state = CliClusterEngine.fetchClusterState(highlight, force)
                val packet = CliIpcPacket.DashState(state)
                writer.println(json.encodeToString<CliIpcPacket>(packet))
            }
            "DASH_SEND" -> {
                val paths = request.args
                val deviceIds = request.payload.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val result = CliClusterEngine.sendLocalPaths(paths, deviceIds)
                writer.println(json.encodeToString<CliIpcPacket>(result))
            }
            "DASH_REMOTE_LIST" -> {
                val deviceId = request.payload
                val path = request.args.firstOrNull().orEmpty()
                val result = CliClusterEngine.listRemoteFiles(deviceId, path)
                writer.println(json.encodeToString<CliIpcPacket>(result))
            }
            "DASH_RETRIEVE" -> {
                val deviceId = request.payload
                val remotePath = request.args.firstOrNull().orEmpty()
                val isDir = request.args.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                val result = CliClusterEngine.retrieveRemoteItem(deviceId, remotePath, isDir)
                writer.println(json.encodeToString<CliIpcPacket>(result))
            }
            "DASH_CLIP_SEND" -> {
                val deviceId = request.payload
                val result = CliClusterEngine.sendClipboard(deviceId)
                writer.println(json.encodeToString<CliIpcPacket>(result))
            }
            "DASH_CLIP_PULL" -> {
                val deviceId = request.payload
                val result = CliClusterEngine.pullClipboard(deviceId)
                writer.println(json.encodeToString<CliIpcPacket>(result))
            }
            "DASH_QUEUE_LIST" -> {
                val queue = CliClusterEngine.getQueue()
                val packet = CliIpcPacket.DashQueueList(queue)
                writer.println(json.encodeToString<CliIpcPacket>(packet))
            }
            "DASH_QUEUE_REMOVE" -> {
                val indices = request.args.mapNotNull { it.toIntOrNull() }
                val result = CliClusterEngine.removeFromQueue(indices)
                writer.println(json.encodeToString<CliIpcPacket>(result))
            }
            else -> {
                val packet = CliIpcPacket.Stderr("Unknown dashboard request type: ${request.requestType}")
                writer.println(json.encodeToString<CliIpcPacket>(packet))
            }
        }
    }
}

