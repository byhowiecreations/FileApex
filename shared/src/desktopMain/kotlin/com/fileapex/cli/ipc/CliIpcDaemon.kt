package com.fileapex.cli.ipc

import com.fileapex.cli.dash.CliClusterEngine
import com.fileapex.cli.commands.CliCommandHandler
import com.fileapex.domain.share.IncomingShareFile
import com.fileapex.domain.share.IncomingSharePayload
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CliIpcDaemon {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null

    private val _incomingCliShares = MutableSharedFlow<IncomingSharePayload>(extraBufferCapacity = 16)
    val incomingCliShares: SharedFlow<IncomingSharePayload> = _incomingCliShares.asSharedFlow()

    fun start() {
        if (serverSocket != null) return
        runCatching {
            serverSocket = ServerSocket(CLI_IPC_PORT, 50, InetAddress.getLoopbackAddress())
            scope.launch {
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(client)
                    }
                }
            }
            println("CliIpcDaemon: Active on 127.0.0.1:$CLI_IPC_PORT")
        }.onFailure { error ->
            println("CliIpcDaemon: Could not bind port $CLI_IPC_PORT :: ${error.message}")
        }
    }

    fun stop() {
        runCatching {
            serverSocket?.close()
            serverSocket = null
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)

            val firstLine = reader.readLine() ?: return
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
                        val responseLine = reader.readLine()
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
            } else {
                // Legacy file paths handoff
                val paths = buildList {
                    if (firstLine.isNotBlank() && File(firstLine).exists()) add(firstLine)
                    while (reader.ready()) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank() && File(line).exists()) add(line)
                    }
                }
                if (paths.isNotEmpty()) {
                    val files = paths.map { path ->
                        val f = File(path)
                        IncomingShareFile(
                            fileName = f.name,
                            absolutePath = f.absolutePath,
                            sizeBytes = if (f.isFile) f.length() else 0L
                        )
                    }
                    _incomingCliShares.tryEmit(
                        IncomingSharePayload(sessionId = UUID.randomUUID().toString(), files = files)
                    )
                }
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

