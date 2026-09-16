package com.fileapex.cli

import com.fileapex.cli.commands.CliCommandHandler
import com.fileapex.cli.dash.CliDashboard
import com.fileapex.cli.dash.IpcClusterBackend
import com.fileapex.cli.dash.StandaloneClusterBackend
import com.fileapex.cli.ipc.CliIpcClient
import com.fileapex.data.bulletin.createBulletinBoardDatabase
import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.di.FileApexServices
import com.fileapex.platform.DesktopJvmStartup
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

object CliRunner {

    /**
     * Entry point for CLI execution.
     * 1. Attempts to forward the command to active GUI app IPC daemon.
     * 2. If daemon is not running, initializes headless services and executes locally.
     * 3. Cleanly terminates the process with the returned exit code.
     */
    fun run(args: Array<String>) {
        val normalizedArgs = CliTokenizer.normalizeArgs(args)
        val cwd = System.getProperty("user.dir") ?: "."

        val first = normalizedArgs.firstOrNull()?.lowercase()
        if (first == "dash" || first == "dashboard") {
            val backend = if (CliIpcClient.isDaemonRunning()) {
                IpcClusterBackend()
            } else {
                ensureHeadlessBootstrap()
                StandaloneClusterBackend()
            }
            CliDashboard(backend, cwd).run()
            exitProcess(0)
        }

        // 1. Try forwarding to active GUI IPC daemon
        val daemonExitCode = CliIpcClient.forwardCommand(normalizedArgs, cwd)
        if (daemonExitCode != null) {
            exitProcess(daemonExitCode)
        }

        // 2. GUI app is not running; execute headlessly
        val exitCode = runHeadless(normalizedArgs, cwd)
        exitProcess(exitCode)
    }

    fun ensureHeadlessBootstrap() {
        DesktopJvmStartup.onMainEntry()

        if (!FileApexServices.isBootstrapComplete) {
            FileApexServices.beginBootstrap(
                createDatabase = { createFileApexDatabase() },
                createBulletinBoard = { createBulletinBoardDatabase() }
            )
            runBlocking {
                FileApexServices.awaitBootstrap()
            }
        }
    }

    fun runHeadless(args: List<String>, cwd: String): Int {
        ensureHeadlessBootstrap()

        val handler = CliCommandHandler(
            out = { println(it) },
            err = { System.err.println(it) },
            onProgress = { percent, speed, eta, _, _ ->
                val barWidth = 28
                if (percent >= 1f) {
                    val bar = "█".repeat(barWidth)
                    print("\r[$bar] 100%\u001b[K")
                    System.out.flush()
                } else {
                    val filled = (percent * barWidth).toInt().coerceIn(0, barWidth)
                    val empty = barWidth - filled
                    val bar = "█".repeat(filled) + "░".repeat(empty)
                    val pct = (percent * 100).toInt()
                    val speedPart = if (speed.isNotBlank()) " $speed" else ""
                    val etaPart = if (eta.isNotBlank()) " • ETA $eta" else ""
                    print("\r[$bar] $pct%$speedPart$etaPart\u001b[K")
                    System.out.flush()
                }
            },
            prompt = { question ->
                print(question)
                System.out.flush()
                kotlin.io.readLine()
            }
        )

        return runBlocking {
            handler.execute(args, cwd)
        }
    }
}
