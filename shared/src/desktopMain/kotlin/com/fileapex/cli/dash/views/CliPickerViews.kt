package com.fileapex.cli.dash.views

import com.fileapex.cli.dash.CliDashboard
import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliDeviceStatus
import com.fileapex.cli.ipc.CliRemoteFile
import java.io.File

internal fun renderSendLocalPickerView(
    sb: StringBuilder,
    terminalCols: Int,
    localCurrentDir: File,
    localEntries: List<File>,
    localCursorIndex: Int,
    localSelectedPaths: Set<String>,
    lastMessage: String?,
    lastMessageIsError: Boolean
) {
    val boxWidth = (terminalCols - 4).coerceIn(60, 88)
    val hLine = "═".repeat(boxWidth - 2)
    sb.append("╔").append(hLine).append("╗\u001b[K\r\n")
    sb.append("║ \u001b[1;32m").append(padEnd("LOCAL FILE & FOLDER PICKER", boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("║ \u001b[90m").append(padEnd("Dir: " + localCurrentDir.absolutePath.takeLast(boxWidth - 10), boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("╚").append(hLine).append("╝\u001b[K\r\n")

    sb.append("Selected items (${localSelectedPaths.size}):\u001b[K\r\n")
    if (localSelectedPaths.isEmpty()) {
        sb.append("  \u001b[90m(None. Press [Space] on items below to select)\u001b[0m\u001b[K\r\n")
    } else {
        localSelectedPaths.take(3).forEach { p ->
            sb.append("  \u001b[32m✔ ${File(p).name}\u001b[0m\u001b[K\r\n")
        }
        if (localSelectedPaths.size > 3) {
            sb.append("  \u001b[90m... and ${localSelectedPaths.size - 3} more\u001b[0m\u001b[K\r\n")
        }
    }
    sb.append("-".repeat(70)).append("\u001b[K\r\n")

    val totalRows = localEntries.size + 1
    val pageSize = 12
    val startIndex = (localCursorIndex - pageSize / 2).coerceIn(0, (totalRows - pageSize).coerceAtLeast(0))
    val endIndex = (startIndex + pageSize).coerceAtMost(totalRows)

    for (i in startIndex until endIndex) {
        val isCursor = (i == localCursorIndex)
        val prefix = if (isCursor) "\u001b[7m > " else "   "
        val suffix = if (isCursor) " \u001b[0m" else ""

        if (i == 0) {
            sb.append("$prefix[DIR] .. (Parent Directory)$suffix\u001b[K\r\n")
        } else {
            val file = localEntries[i - 1]
            val selected = localSelectedPaths.contains(file.absolutePath)
            val check = if (selected) "[✔] " else "[ ] "
            val type = if (file.isDirectory) "[DIR] " else "      "
            val size = if (file.isFile) " (${CliDashboard.formatBytes(file.length())})" else ""
            sb.append("$prefix$check$type${file.name}$size$suffix\u001b[K\r\n")
        }
    }

    if (lastMessage != null) {
        val color = if (lastMessageIsError) "\u001b[31m" else "\u001b[32m"
        sb.append("\u001b[K\r\n$color>> $lastMessage\u001b[0m\u001b[K\r\n")
    }

    sb.append("\u001b[K\r\n\u001b[7m [↑/↓] Navigate  [Space] Select  [Enter] Enter Folder  [P] Path  [A] All  [C] Confirm  [Esc] Cancel \u001b[0m\u001b[K\r\n")
}

internal fun renderSendTargetPickerView(
    sb: StringBuilder,
    terminalCols: Int,
    clusterState: CliClusterState,
    targetCursorIndex: Int,
    targetSelectedDeviceIds: Set<String>
) {
    val boxWidth = (terminalCols - 4).coerceIn(60, 88)
    val hLine = "═".repeat(boxWidth - 2)
    sb.append("╔").append(hLine).append("╗\u001b[K\r\n")
    sb.append("║ \u001b[1;32m").append(padEnd("SELECT DESTINATION PEER(S)", boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("║ \u001b[90m").append(padEnd("Target devices will receive files/folders recursively", boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("╚").append(hLine).append("╝\u001b[K\r\n\u001b[K\r\n")

    val devices = clusterState.devices
    for ((idx, dev) in devices.withIndex()) {
        val isCursor = (idx == targetCursorIndex)
        val selected = targetSelectedDeviceIds.contains(dev.deviceId)
        val check = if (selected) "[✔] " else "[ ] "
        val prefix = if (isCursor) "\u001b[7m > " else "   "
        val suffix = if (isCursor) " \u001b[0m" else ""

        val statusColor = when (dev.status) {
            "ONLINE" -> "\u001b[32m"
            "CELLULAR" -> "\u001b[33m"
            else -> "\u001b[90m"
        }
        sb.append("$prefix$check${dev.slugOrAlias} (${dev.deviceName}) - $statusColor${dev.status}\u001b[0m$suffix\u001b[K\r\n")
    }

    sb.append("\u001b[K\r\n\u001b[7m [↑/↓] Navigate  [Space] Toggle  [Enter] Send Now  [Esc] Back \u001b[0m\u001b[K\r\n")
}

internal fun renderRetrieveDevicePickerView(
    sb: StringBuilder,
    terminalCols: Int,
    clusterState: CliClusterState,
    targetCursorIndex: Int
) {
    val boxWidth = (terminalCols - 4).coerceIn(60, 88)
    val hLine = "═".repeat(boxWidth - 2)
    sb.append("╔").append(hLine).append("╗\u001b[K\r\n")
    sb.append("║ \u001b[1;34m").append(padEnd("RETRIEVE FILE - SELECT PEER TO BROWSE", boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("╚").append(hLine).append("╝\u001b[K\r\n\u001b[K\r\n")

    val onlineDevices = clusterState.devices.filter { it.status == "ONLINE" || it.status == "CELLULAR" }
    for ((idx, dev) in onlineDevices.withIndex()) {
        val isCursor = (idx == targetCursorIndex)
        val prefix = if (isCursor) "\u001b[7m > " else "   "
        val suffix = if (isCursor) " \u001b[0m" else ""
        sb.append("$prefix${dev.slugOrAlias} (${dev.deviceName}) - \u001b[32m${dev.status}\u001b[0m$suffix\u001b[K\r\n")
    }

    sb.append("\u001b[K\r\n\u001b[7m [↑/↓] Navigate  [Enter] Open Remote Directory  [Esc] Cancel \u001b[0m\u001b[K\r\n")
}

internal fun renderRetrieveRemoteBrowserView(
    sb: StringBuilder,
    terminalCols: Int,
    dev: CliDeviceStatus,
    remoteCurrentPath: String,
    remoteEntries: List<CliRemoteFile>,
    remoteCursorIndex: Int
) {
    val boxWidth = (terminalCols - 4).coerceIn(60, 88)
    val hLine = "═".repeat(boxWidth - 2)
    sb.append("╔").append(hLine).append("╗\u001b[K\r\n")
    sb.append("║ \u001b[1;34m").append(padEnd("REMOTE BROWSER: " + dev.slugOrAlias, boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("║ \u001b[90m").append(padEnd("Remote Path: " + remoteCurrentPath.takeLast(boxWidth - 16), boxWidth - 4)).append("\u001b[0m ║\u001b[K\r\n")
    sb.append("╚").append(hLine).append("╝\u001b[K\r\n")

    val totalRows = remoteEntries.size + 1
    val pageSize = 12
    val startIndex = (remoteCursorIndex - pageSize / 2).coerceIn(0, (totalRows - pageSize).coerceAtLeast(0))
    val endIndex = (startIndex + pageSize).coerceAtMost(totalRows)

    for (i in startIndex until endIndex) {
        val isCursor = (i == remoteCursorIndex)
        val prefix = if (isCursor) "\u001b[7m > " else "   "
        val suffix = if (isCursor) " \u001b[0m" else ""

        if (i == 0) {
            sb.append("$prefix[DIR] .. (Parent Directory)$suffix\u001b[K\r\n")
        } else {
            val item = remoteEntries[i - 1]
            val type = if (item.isDirectory) "[DIR] " else "      "
            val size = if (!item.isDirectory) " (${CliDashboard.formatBytes(item.sizeBytes)})" else ""
            sb.append("$prefix$type${item.name}$size$suffix\u001b[K\r\n")
        }
    }

    sb.append("\r\n\u001b[7m [↑/↓] Navigate  [Enter] Open Folder  [D] Download to Local Downloads  [Esc] Back \u001b[0m\u001b[K\r\n")
}
