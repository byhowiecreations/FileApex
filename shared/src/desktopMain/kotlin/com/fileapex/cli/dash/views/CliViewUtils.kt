package com.fileapex.cli.dash.views

import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.update.FileApexAppVersion

internal fun padEnd(s: String, len: Int): String {
    return if (s.length >= len) s.take(len) else s + " ".repeat(len - s.length)
}

internal fun renderDashboardHeader(sb: StringBuilder, terminalCols: Int, clusterState: CliClusterState) {
    val boxWidth = (terminalCols - 4).coerceIn(60, 88)
    val innerWidth = boxWidth - 4
    val hLine = "═".repeat(boxWidth - 2)
    val title = "FILEAPEX DASHBOARD v${FileApexAppVersion.NAME}"
    val stats = "Peers: ${clusterState.onlinePeerCount}/${clusterState.totalPeerCount} Online  •  Transfers: ${clusterState.activeTransfersCount}  •  Queue: ${clusterState.queueCount}"

    sb.append("╔").append(hLine).append("╗\u001b[K\r\n")
    sb.append("║ \u001b[1;36m").append(padEnd(title, innerWidth)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("║ \u001b[90m").append(padEnd(stats, innerWidth)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("╚").append(hLine).append("╝\u001b[K\r\n")
}
