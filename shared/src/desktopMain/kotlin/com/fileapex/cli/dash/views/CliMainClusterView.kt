package com.fileapex.cli.dash.views

import com.fileapex.cli.ipc.CliClusterState

internal fun renderMainClusterView(
    sb: StringBuilder,
    terminalCols: Int,
    terminalRows: Int,
    clusterState: CliClusterState,
    lastMessage: String?,
    lastMessageIsError: Boolean
) {
    renderDashboardHeader(sb, terminalCols, clusterState)

    val isCompact = terminalRows <= 28
    val spacing = if (isCompact) "" else "\u001b[K\r\n"

    // Device Table
    sb.append(spacing).append("\u001b[1m")
    val headerRow = String.format("%-4s %-24s %-16s %-12s %-10s", "ID", "SLUG / ALIAS", "BATTERY", "CLIPBOARD", "STATUS")
    sb.append(headerRow).append("\u001b[0m\u001b[K\r\n")
    sb.append("-".repeat(70)).append("\u001b[K\r\n")

    val devices = clusterState.devices
    if (devices.isEmpty()) {
        sb.append("No authenticated devices in cluster.\u001b[K\r\n")
    } else {
        val maxVisibleDevices = if (isCompact) (terminalRows - 14).coerceAtLeast(4) else devices.size
        val visibleDevices = devices.take(maxVisibleDevices)
        for (dev in visibleDevices) {
            val statusColor = when (dev.status) {
                "ONLINE" -> "\u001b[32m"
                "CELLULAR" -> "\u001b[33m"
                else -> "\u001b[90m"
            }
            val row = String.format(
                "%-4s %-24s %-16s %-12s %s%-10s\u001b[0m",
                dev.id,
                dev.slugOrAlias.take(23),
                dev.batteryBlocks,
                dev.clipboardStatus,
                statusColor,
                dev.status
            )
            sb.append(row).append("\u001b[K\r\n")
        }
        if (devices.size > maxVisibleDevices) {
            sb.append("\u001b[90m... and ${devices.size - maxVisibleDevices} more devices\u001b[0m\u001b[K\r\n")
        }
    }

    // Active Transfers Section
    val activeList = clusterState.activeTransfers
    val transferCount = if (activeList.isNotEmpty()) activeList.size else clusterState.activeTransfersCount
    if (transferCount > 1) {
        sb.append(spacing).append("\u001b[1mACTIVE TRANSFERS ($transferCount):\u001b[0m\u001b[K\r\n")
    } else {
        sb.append(spacing).append("\u001b[1mACTIVE TRANSFERS:\u001b[0m\u001b[K\r\n")
    }

    if (activeList.isNotEmpty()) {
        val barWidth = 14
        for (t in activeList) {
            val p = t.progress
            val filled = (p * barWidth).toInt().coerceIn(0, barWidth)
            val empty = barWidth - filled
            val bar = "█".repeat(filled) + "░".repeat(empty)
            val pct = (p * 100).toInt()
            val isDone = p >= 1f || t.speed.equals("Completed", ignoreCase = true)
            val statusText = if (isDone) "\u001b[1;32mCompleted\u001b[0m" else "$pct%"
            val speed = if (t.speed.isNotBlank() && !isDone) " • ${t.speed}" else ""
            val eta = if (t.eta.isNotBlank() && !isDone) " • ETA ${t.eta}" else ""
            val bytes = if (t.bytesFormatted.isNotBlank()) " • ${t.bytesFormatted}" else ""
            val dest = if (t.deviceName.isNotBlank()) " -> ${t.deviceName.take(20)}" else ""
            val file = if (t.fileName.isNotBlank()) " [${t.fileName.take(20)}]" else ""

            val colorCode = if (isDone) "\u001b[1;32m" else "\u001b[1;36m"
            sb.append("$colorCode[$bar]\u001b[0m $statusText$speed$bytes$dest$file\u001b[0m\u001b[K\r\n")
        }
    } else if (clusterState.activeTransfersCount > 0) {
        val p = clusterState.activeTransferProgress
        if (p != null) {
            val barWidth = 16
            val filled = (p * barWidth).toInt().coerceIn(0, barWidth)
            val empty = barWidth - filled
            val bar = "█".repeat(filled) + "░".repeat(empty)
            val pct = (p * 100).toInt()
            val isDone = p >= 1f
            val statusText = if (isDone) "\u001b[1;32mCompleted\u001b[0m" else "$pct%"
            val speed = if (clusterState.activeTransferSpeed.isNotBlank() && !isDone) " • ${clusterState.activeTransferSpeed}" else ""
            val eta = if (clusterState.activeTransferEta.isNotBlank() && !isDone) " • ETA ${clusterState.activeTransferEta}" else ""
            val bytes = if (clusterState.activeTransferBytesFormatted.isNotBlank()) " • ${clusterState.activeTransferBytesFormatted}" else ""
            val label = clusterState.activeTransferLabel.ifBlank { clusterState.activeTransferSummary }
            val targetSuffix = if (label.isNotBlank()) " -> ${label.take(25)}" else ""

            val colorCode = if (isDone) "\u001b[1;32m" else "\u001b[1;36m"
            sb.append("$colorCode[$bar]\u001b[0m $statusText$speed$bytes$targetSuffix\u001b[0m\u001b[K\r\n")
        } else {
            sb.append("\u001b[1;36m[Connecting / Preparing transfer...]\u001b[0m\u001b[K\r\n")
        }
    } else {
        sb.append("\u001b[90mNo active transfers\u001b[0m\u001b[K\r\n")
    }

    // Message banner if any
    if (lastMessage != null) {
        val color = if (lastMessageIsError) "\u001b[31m" else "\u001b[32m"
        sb.append(spacing).append("$color>> $lastMessage\u001b[0m\u001b[K\r\n")
    }

    // Footer
    sb.append(spacing).append("\u001b[7m [L] Devices  [S] Send  [R] Retrieve  [C] Clipboard  [B] Battery  [Q] Queue  [H] Help  [Esc] Exit \u001b[0m\u001b[K\r\n")
}
