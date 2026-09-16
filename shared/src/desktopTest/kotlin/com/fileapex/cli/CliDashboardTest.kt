package com.fileapex.cli

import com.fileapex.cli.dash.CliClusterBackend
import com.fileapex.cli.dash.CliClusterEngine
import com.fileapex.cli.dash.CliDashboard
import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliDeviceStatus
import com.fileapex.cli.ipc.CliIpcPacket
import com.fileapex.cli.ipc.CliQueueItem
import com.fileapex.cli.ipc.CliRemoteFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliDashboardTest {

    @Test
    fun testBatteryBlockFormatting() {
        // 62% discharging -> 3 filled blocks, 2 empty
        assertEquals("[■■■□□] 62%", CliClusterEngine.formatBatteryBlocks(62, "Discharging"))

        // 20% -> 1 filled block, 4 empty
        assertEquals("[■□□□□] 20%", CliClusterEngine.formatBatteryBlocks(20, "Battery"))

        // 99%+ AC -> [AC 100%]
        assertEquals("[AC 100%]", CliClusterEngine.formatBatteryBlocks(100, "AC"))
        assertEquals("[AC 100%]", CliClusterEngine.formatBatteryBlocks(99, "AC"))

        // 75% AC -> [■■■□□] AC 75%
        assertEquals("[■■■□□] AC 75%", CliClusterEngine.formatBatteryBlocks(75, "AC"))

        // Null or negative
        assertEquals("[-----] --", CliClusterEngine.formatBatteryBlocks(null, ""))
        assertEquals("[-----] --", CliClusterEngine.formatBatteryBlocks(-1, ""))
    }

    @Test
    fun testQueueIndexRangeParser() {
        assertEquals(listOf(1), CliDashboard.parseIndexRange("1", 5))
        assertEquals(listOf(2, 3, 4), CliDashboard.parseIndexRange("2-4", 5))
        assertEquals(emptyList<Int>(), CliDashboard.parseIndexRange("0", 5))
        assertEquals(emptyList<Int>(), CliDashboard.parseIndexRange("6", 5))
        assertEquals(emptyList<Int>(), CliDashboard.parseIndexRange("5-2", 5))
        assertEquals(emptyList<Int>(), CliDashboard.parseIndexRange("invalid", 5))
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", CliDashboard.formatBytes(0))
        assertEquals("1 KB", CliDashboard.formatBytes(1024))
        assertEquals("1 MB", CliDashboard.formatBytes(1048576))
        assertEquals("1.5 MB", CliDashboard.formatBytes(1572864))
    }

    @Test
    fun testMockBackendClusterState() = runBlocking {
        val mockBackend = object : CliClusterBackend {
            override suspend fun fetchClusterState(highlightBattery: Boolean, force: Boolean): CliClusterState {
                return CliClusterState(
                    onlinePeerCount = 1,
                    totalPeerCount = 2,
                    activeTransfersCount = 1,
                    queueCount = 0,
                    activeTransferProgress = 0.64f,
                    activeTransferSpeed = "38.4 MB/s",
                    activeTransferEta = "00:08",
                    activeTransferSummary = "photo.jpg -> Phone",
                    devices = listOf(
                        CliDeviceStatus(
                            id = 1,
                            deviceId = "dev-1",
                            deviceName = "Pixel 8 Pro",
                            slugOrAlias = "pixel8",
                            batteryBlocks = "[■■■□□] 62%",
                            batteryPercent = 62,
                            chargingState = "Discharging",
                            clipboardStatus = "Enabled",
                            status = "ONLINE",
                            host = "192.168.1.50",
                            port = 49428
                        ),
                        CliDeviceStatus(
                            id = 2,
                            deviceId = "dev-2",
                            deviceName = "MacBook Pro",
                            slugOrAlias = "mbp",
                            batteryBlocks = "[AC 100%]",
                            batteryPercent = 100,
                            chargingState = "AC",
                            clipboardStatus = "Disabled",
                            status = "OFFLINE"
                        )
                    )
                )
            }

            override suspend fun sendLocalPaths(paths: List<String>, deviceIds: List<String>): CliIpcPacket.DashActionResult =
                CliIpcPacket.DashActionResult(true, "Sent")

            override suspend fun listRemoteFiles(deviceId: String, path: String): CliIpcPacket.DashRemoteList =
                CliIpcPacket.DashRemoteList(path, emptyList())

            override suspend fun retrieveRemoteItem(deviceId: String, remotePath: String, isDirectory: Boolean): CliIpcPacket.DashActionResult =
                CliIpcPacket.DashActionResult(true, "Retrieved")

            override suspend fun sendClipboard(deviceId: String): CliIpcPacket.DashActionResult =
                CliIpcPacket.DashActionResult(true, "Clipboard sent")

            override suspend fun pullClipboard(deviceId: String): CliIpcPacket.DashClipPullResult =
                CliIpcPacket.DashClipPullResult("Copied text")

            override suspend fun getQueue(): List<CliQueueItem> = emptyList()

            override suspend fun removeFromQueue(indices: List<Int>): CliIpcPacket.DashActionResult =
                CliIpcPacket.DashActionResult(true, "Removed")
        }

        val state = mockBackend.fetchClusterState()
        assertEquals(1, state.onlinePeerCount)
        assertEquals(2, state.totalPeerCount)
        assertEquals(1, state.activeTransfersCount)
        assertEquals(0.64f, state.activeTransferProgress ?: 0f, 0.01f)

        // Ensure slug/alias does not leak IP
        assertFalse(state.devices[0].slugOrAlias.contains("192.168"))
        assertEquals("pixel8", state.devices[0].slugOrAlias)
        assertEquals("[■■■□□] 62%", state.devices[0].batteryBlocks)
        assertEquals("ONLINE", state.devices[0].status)
    }

    @Test
    fun testMultiTargetTransfersState() {
        val transfer1 = com.fileapex.cli.ipc.CliActiveTransfer(
            deviceId = "dev-1",
            deviceName = "Samsung Fold8",
            fileName = "update.apk",
            progress = 0.45f,
            speed = "12.4 MB/s",
            eta = "00:04",
            bytesFormatted = "45.2 MB / 86.8 MB"
        )
        val transfer2 = com.fileapex.cli.ipc.CliActiveTransfer(
            deviceId = "dev-2",
            deviceName = "Motorola Razr",
            fileName = "update.apk",
            progress = 0.68f,
            speed = "18.1 MB/s",
            eta = "00:02",
            bytesFormatted = "59.0 MB / 86.8 MB"
        )
        val state = CliClusterState(
            onlinePeerCount = 2,
            totalPeerCount = 2,
            activeTransfersCount = 2,
            queueCount = 0,
            activeTransfers = listOf(transfer1, transfer2)
        )
        assertEquals(2, state.activeTransfers.size)
        assertEquals("Samsung Fold8", state.activeTransfers[0].deviceName)
        assertEquals("Motorola Razr", state.activeTransfers[1].deviceName)
        assertEquals(0.45f, state.activeTransfers[0].progress, 0.01f)
        assertEquals(0.68f, state.activeTransfers[1].progress, 0.01f)
    }
}
