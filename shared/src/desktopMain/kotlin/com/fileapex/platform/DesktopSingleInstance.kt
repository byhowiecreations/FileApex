package com.fileapex.platform

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

object DesktopSingleInstance {
    private const val IPC_PORT = 49429
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _incomingCliShares = MutableSharedFlow<IncomingSharePayload>(extraBufferCapacity = 16)
    val incomingCliShares: SharedFlow<IncomingSharePayload> = _incomingCliShares.asSharedFlow()

    private var serverSocket: ServerSocket? = null

    /**
     * Checks if FileApex is already running.
     * If ALREADY running: sends [args] file paths to primary instance via local IPC and returns `true`.
     * If PRIMARY instance: starts local IPC server listener and returns `false`.
     */
    fun handleSingleInstanceOrHandoff(args: Array<String>): Boolean {
        if (!DesktopPlatformPaths.isWindows()) return false
        val validPaths = args.filter { File(it).exists() }

        return runCatching {
            val socket = Socket(InetAddress.getLoopbackAddress(), IPC_PORT)
            socket.use { s ->
                val writer = PrintWriter(s.getOutputStream(), true)
                writer.println(validPaths.joinToString("\n"))
            }
            println("DesktopSingleInstance: Handed off ${validPaths.size} path(s) to running FileApex instance.")
            true
        }.getOrElse {
            startIpcListener()
            false
        }
    }

    private fun startIpcListener() {
        runCatching {
            serverSocket = ServerSocket(IPC_PORT, 50, InetAddress.getLoopbackAddress())
            scope.launch {
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch {
                        handleIpcClient(client)
                    }
                }
            }
            println("DesktopSingleInstance: Primary instance IPC listener active on port $IPC_PORT.")
        }.onFailure { error ->
            println("DesktopSingleInstance: Could not bind IPC port $IPC_PORT :: ${error.message}")
        }
    }

    private fun handleIpcClient(socket: Socket) {
        runCatching {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val paths = reader.readLines().filter { it.isNotBlank() && File(it).exists() }
                if (paths.isNotEmpty()) {
                    val files = paths.map { path ->
                        val f = File(path)
                        IncomingShareFile(
                            fileName = f.name,
                            absolutePath = f.absolutePath,
                            sizeBytes = if (f.isFile) f.length() else 0L
                        )
                    }
                    val payload = IncomingSharePayload(
                        sessionId = UUID.randomUUID().toString(),
                        files = files
                    )
                    _incomingCliShares.tryEmit(payload)
                }
            }
        }
    }
}
