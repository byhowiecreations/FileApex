package com.fileapex.cli.dash.views

import com.fileapex.cli.ipc.CliClusterState

internal fun renderBatteryMetricsView(
    sb: StringBuilder,
    terminalCols: Int,
    clusterState: CliClusterState
) {
    renderDashboardHeader(sb, terminalCols, clusterState)
    sb.append("\u001b[K\r\n\u001b[1;33mBATTERY & POWER METRICS BREAKDOWN:\u001b[0m\u001b[K\r\n")
    sb.append(String.format("%-4s %-24s %-18s %-14s %-10s", "ID", "SLUG / ALIAS", "BATTERY BLOCKS", "CHARGING STATE", "STATUS")).append("\u001b[K\r\n")
    sb.append("-".repeat(74)).append("\u001b[K\r\n")

    for (dev in clusterState.devices) {
        val state = dev.chargingState.ifBlank { if (dev.status == "OFFLINE") "Unreachable" else "Battery" }
        val row = String.format(
            "%-4s %-24s \u001b[33m%-18s\u001b[0m %-14s %-10s",
            dev.id,
            dev.slugOrAlias.take(23),
            dev.batteryBlocks,
            state,
            dev.status
        )
        sb.append(row).append("\u001b[K\r\n")
    }

    sb.append("\u001b[K\r\n\u001b[7m [Esc / B / L] Return to Main Dashboard \u001b[0m\u001b[K\r\n")
}
