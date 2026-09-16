package com.fileapex.cli.ipc

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CliIpcClient {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Attempts to forward the command to the active GUI app daemon.
     * Returns the exit code if handled by daemon, or null if daemon is not running.
     */
    fun forwardCommand(args: List<String>, cwd: String): Int? {
        return runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), CLI_IPC_PORT), 500)
            socket.use { s ->
                val writer = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                val request = CliRequest(args = args, cwd = cwd)
                writer.println(json.encodeToString(request))

                var lastWasProgress = false
                while (true) {
                    val line = reader.readLine() ?: break
                    val packet = runCatching {
                        json.decodeFromString<CliIpcPacket>(line)
                    }.getOrNull() ?: continue

                    when (packet) {
                        is CliIpcPacket.Stdout -> {
                            if (lastWasProgress) {
                                println()
                                lastWasProgress = false
                            }
                            println(packet.text)
                        }
                        is CliIpcPacket.Stderr -> {
                            if (lastWasProgress) {
                                println()
                                lastWasProgress = false
                            }
                            System.err.println(packet.text)
                        }
                        is CliIpcPacket.Progress -> {
                            renderProgressBar(packet)
                            lastWasProgress = true
                        }
                        is CliIpcPacket.Prompt -> {
                            if (lastWasProgress) {
                                println()
                                lastWasProgress = false
                            }
                            print(packet.question)
                            System.out.flush()
                            val answer = kotlin.io.readLine().orEmpty()
                            val inputPacket = CliIpcPacket.Input(answer)
                            writer.println(json.encodeToString<CliIpcPacket>(inputPacket))
                        }
                        is CliIpcPacket.Input -> {
                            // Daemon to client input not expected
                        }
                        is CliIpcPacket.Exit -> {
                            if (lastWasProgress) {
                                println()
                            }
                            return packet.code
                        }
                        else -> {
                            // Dashboard packets not processed in legacy forwardCommand
                        }
                    }
                }
                0
            }
        }.getOrNull()
    }

    private fun renderProgressBar(p: CliIpcPacket.Progress) {
        val barWidth = 28
        if (p.percent >= 1f) {
            val bar = "█".repeat(barWidth)
            print("\r[$bar] 100%\u001b[K")
            System.out.flush()
            return
        }
        val filled = (p.percent * barWidth).toInt().coerceIn(0, barWidth)
        val empty = barWidth - filled
        val bar = "█".repeat(filled) + "░".repeat(empty)
        val pct = (p.percent * 100).toInt()
        val speedPart = if (p.speed.isNotBlank()) " ${p.speed}" else ""
        val etaPart = if (p.eta.isNotBlank()) " • ETA ${p.eta}" else ""
        print("\r[$bar] $pct%$speedPart$etaPart\u001b[K")
        System.out.flush()
    }

    fun isDaemonRunning(): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), CLI_IPC_PORT), 300)
                true
            }
        }.getOrDefault(false)
    }

    fun sendDashboardRequest(request: CliRequest, timeoutMs: Int = 4000): CliIpcPacket? {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), CLI_IPC_PORT), 800)
                s.soTimeout = timeoutMs
                val writer = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                writer.println(json.encodeToString(request))
                val line = reader.readLine() ?: return null
                json.decodeFromString<CliIpcPacket>(line)
            }
        }.getOrNull()
    }
}

