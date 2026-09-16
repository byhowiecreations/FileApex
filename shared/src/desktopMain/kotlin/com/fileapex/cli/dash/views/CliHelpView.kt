package com.fileapex.cli.dash.views

import com.fileapex.cli.ipc.CliClusterState

internal fun renderHelpView(
    sb: StringBuilder,
    terminalCols: Int,
    clusterState: CliClusterState
) {
    renderDashboardHeader(sb, terminalCols, clusterState)
    sb.append("\u001b[K\r\n\u001b[1;36mKEYBOARD SHORTCUTS & NAVIGATION REFERENCE:\u001b[0m\u001b[K\r\n\u001b[K\r\n")
    val shortcuts = listOf(
        "[L]" to "Refresh active cluster status and return to main dashboard home view.",
        "[S]" to "Send files/folders: open in-terminal local file browser and target picker.",
        "[R]" to "Retrieve files: browse remote directories on target peer and download locally.",
        "[C]" to "Clipboard manager: push local clipboard to peer or pull remote clipboard.",
        "[B]" to "Battery breakdown: view power levels and charging state across all peers.",
        "[Q]" to "Queue manager: view pending/offline transfers and remove single items or ranges.",
        "[H]" to "Help overlay: display this guide.",
        "[Esc / Ctrl+C]" to "Cleanly restore terminal state, disconnect IPC, and exit."
    )

    for ((key, desc) in shortcuts) {
        sb.append(String.format("  \u001b[1m%-16s\u001b[0m %s\r\n\u001b[K", key, desc))
    }

    sb.append("\u001b[K\r\n\u001b[7m Press any key to close this help menu \u001b[0m\u001b[K\r\n")
}
