package com.fileapex.cli

import com.fileapex.cli.dash.CliDashboard
import com.fileapex.cli.dash.IpcClusterBackend
import com.fileapex.cli.ipc.CliIpcClient
import kotlin.system.exitProcess

object CliRunner {

    fun run(args: Array<String>) {
        val normalizedArgs = CliTokenizer.normalizeArgs(args)
        val first = normalizedArgs.firstOrNull()?.lowercase()
        if (first == null || first == "help" || first == "-h" || first == "--help") {
            println(HELP_TEXT)
            exitProcess(0)
        }

        val cwd = System.getProperty("user.dir") ?: "."
        if (first == "dash" || first == "dashboard") {
            if (!CliIpcClient.isDaemonRunning()) {
                System.err.println("Open FileApex before using the CLI.")
                exitProcess(1)
            }
            CliDashboard(IpcClusterBackend(), cwd).run()
            exitProcess(0)
        }

        val exitCode = runHeadless(normalizedArgs, cwd)
        exitProcess(exitCode)
    }

    fun runHeadless(args: List<String>, cwd: String, forward: (List<String>, String) -> Int? = { input, directory -> CliIpcClient.forwardCommand(input, directory) }): Int {
        return forward(args, cwd) ?: run {
            System.err.println("Open FileApex before using the CLI. No standalone app was started.")
            1
        }
    }

    val HELP_TEXT = """
FileApex CLI - Local-First P2P File Explorer & Transfer
The FileApex app must be running. Commands use its database and transfer engine.

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
