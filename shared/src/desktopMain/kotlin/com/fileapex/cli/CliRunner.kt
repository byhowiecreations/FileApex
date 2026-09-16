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
        val first = normalizedArgs.firstOrNull()?.lowercase()
        if (first == null || first == "help" || first == "-h" || first == "--help") {
            println(HELP_TEXT)
            exitProcess(0)
        }

        val cwd = System.getProperty("user.dir") ?: "."
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
            com.fileapex.cli.dash.CliClusterEngine.ensureLiveMirror()
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

    val HELP_TEXT = """
FileApex CLI - Local-First P2P File Explorer & Transfer

Usage:
  fileapex <command> [options] [arguments]

Commands:
  help, -h
      Display this overview of all available commands and options.

  dash, dashboard
      Launch interactive Terminal User Interface (TUI) dashboard to monitor cluster,
      manage peers, track streaming transfers, browse remote files, and control queue.

  devices
      Output formatted table of authenticated paired devices, including
      generated slugs/aliases, online status, and battery percentage.

  alias [full_name] [short_slug]
      Map a user-defined short slug to a paired device.

  send <file path> [alias] OR send [alias] <file path>
      Send a file to destination, or if you use 'bb' for [alias] it will go to the Bulletin Board on all devices. The app will understand it in any order the same way (send local to destination).

  send "message in quotes" [alias] OR send [alias] "message in quotes"
      Send a message to the destination device, or to 'bb' for Bulletin Board.

  send clip [alias] OR send [alias] clip
      Send current local clipboard content to destination device.

  send beep [alias] OR send [alias] beep
      FileApex will send an audible 'beep' to the destination if you need to locate it.

  clip [device/alias]
      Interactive prompt to send or retrieve clipboard with target device.

  clip send [device/alias]
      Sends current clipboard content to destination device.

  clip push [device/alias]
      Does the same thing.

  clip pull [device/alias]
      Retrieves remote clipboard content from destination device (if Android, keep FileApex in foreground).

  clip rec [device/alias]
      Does the same thing.

  clip receive [device/alias]
      Does the same thing.

  clip retrieve [device/alias]
      Does the same thing.

  queue
      Output formatted list of all pending transfers in the queue.

  queue remove <index> or <range> (e.g. 2-4)
      Remove single transfer by index or a range of items from the queue.

  queue rem <index> or <range> (e.g. 2-4)
      Does the same thing.
""".trimIndent()
}
