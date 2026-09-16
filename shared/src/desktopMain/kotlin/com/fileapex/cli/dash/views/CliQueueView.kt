package com.fileapex.cli.dash.views

import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliQueueItem

internal fun renderQueueView(
    sb: StringBuilder,
    terminalCols: Int,
    clusterState: CliClusterState,
    queue: List<CliQueueItem>,
    lastMessage: String?,
    lastMessageIsError: Boolean
) {
    renderDashboardHeader(sb, terminalCols, clusterState)
    sb.append("\u001b[K\r\n\u001b[1;35mPENDING TRANSFER QUEUE:\u001b[0m\u001b[K\r\n")

    if (queue.isEmpty()) {
        sb.append("\u001b[90mTransfer queue is empty.\u001b[0m\u001b[K\r\n")
    } else {
        sb.append(String.format("%-6s %-36s %-24s", "INDEX", "SOURCE SUMMARY", "TARGET DEVICE(S)")).append("\u001b[K\r\n")
        sb.append("-".repeat(72)).append("\u001b[K\r\n")
        for (item in queue) {
            val err = item.lastError?.let { " ($it)" }.orEmpty()
            sb.append(String.format("[%-4s] %-36s %-24s", item.index, item.sourceSummary.take(35), (item.targetDevices + err).take(23))).append("\u001b[K\r\n")
        }
    }

    if (lastMessage != null) {
        val color = if (lastMessageIsError) "\u001b[31m" else "\u001b[32m"
        sb.append("\u001b[K\r\n$color>> $lastMessage\u001b[0m\u001b[K\r\n")
    }

    sb.append("\u001b[K\r\n\u001b[7m [R / D] Remove Item(s)  [Esc / L] Return to Main Dashboard \u001b[0m\u001b[K\r\n")
}
