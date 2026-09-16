package com.fileapex.cli.dash

import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliIpcClient
import com.fileapex.cli.ipc.CliIpcPacket
import com.fileapex.cli.ipc.CliQueueItem
import com.fileapex.cli.ipc.CliRemoteFile
import com.fileapex.cli.ipc.CliRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CliClusterBackend {
    suspend fun fetchClusterState(highlightBattery: Boolean = false, force: Boolean = false): CliClusterState
    suspend fun sendLocalPaths(paths: List<String>, deviceIds: List<String>): CliIpcPacket.DashActionResult
    suspend fun listRemoteFiles(deviceId: String, path: String): CliIpcPacket.DashRemoteList
    suspend fun retrieveRemoteItem(deviceId: String, remotePath: String, isDirectory: Boolean): CliIpcPacket.DashActionResult
    suspend fun sendClipboard(deviceId: String): CliIpcPacket.DashActionResult
    suspend fun pullClipboard(deviceId: String): CliIpcPacket.DashClipPullResult
    suspend fun getQueue(): List<CliQueueItem>
    suspend fun removeFromQueue(indices: List<Int>): CliIpcPacket.DashActionResult
}

class IpcClusterBackend : CliClusterBackend {
    private var lastState = CliClusterState(0, 0, 0, 0)

    override suspend fun fetchClusterState(highlightBattery: Boolean, force: Boolean): CliClusterState = withContext(Dispatchers.IO) {
        val payload = "$highlightBattery,$force"
        val req = CliRequest(requestType = "DASH_STATE", payload = payload)
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 3000)
        if (packet is CliIpcPacket.DashState) {
            lastState = packet.state
            packet.state
        } else {
            lastState
        }
    }

    override suspend fun sendLocalPaths(paths: List<String>, deviceIds: List<String>): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val req = CliRequest(args = paths, requestType = "DASH_SEND", payload = deviceIds.joinToString(","))
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 60000)
        (packet as? CliIpcPacket.DashActionResult) ?: CliIpcPacket.DashActionResult(false, "Main app communication timed out.")
    }

    override suspend fun listRemoteFiles(deviceId: String, path: String): CliIpcPacket.DashRemoteList = withContext(Dispatchers.IO) {
        val req = CliRequest(args = listOf(path), requestType = "DASH_REMOTE_LIST", payload = deviceId)
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 5000)
        (packet as? CliIpcPacket.DashRemoteList) ?: CliIpcPacket.DashRemoteList(currentPath = path, items = emptyList())
    }

    override suspend fun retrieveRemoteItem(deviceId: String, remotePath: String, isDirectory: Boolean): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val req = CliRequest(args = listOf(remotePath, isDirectory.toString()), requestType = "DASH_RETRIEVE", payload = deviceId)
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 30000)
        (packet as? CliIpcPacket.DashActionResult) ?: CliIpcPacket.DashActionResult(false, "Failed to retrieve from main app.")
    }

    override suspend fun sendClipboard(deviceId: String): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val req = CliRequest(requestType = "DASH_CLIP_SEND", payload = deviceId)
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 5000)
        (packet as? CliIpcPacket.DashActionResult) ?: CliIpcPacket.DashActionResult(false, "Failed to push clipboard via main app.")
    }

    override suspend fun pullClipboard(deviceId: String): CliIpcPacket.DashClipPullResult = withContext(Dispatchers.IO) {
        val req = CliRequest(requestType = "DASH_CLIP_PULL", payload = deviceId)
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 5000)
        (packet as? CliIpcPacket.DashClipPullResult) ?: CliIpcPacket.DashClipPullResult(null, "Failed to pull clipboard via main app.")
    }

    override suspend fun getQueue(): List<CliQueueItem> = withContext(Dispatchers.IO) {
        val req = CliRequest(requestType = "DASH_QUEUE_LIST")
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 3000)
        (packet as? CliIpcPacket.DashQueueList)?.items ?: emptyList()
    }

    override suspend fun removeFromQueue(indices: List<Int>): CliIpcPacket.DashActionResult = withContext(Dispatchers.IO) {
        val req = CliRequest(args = indices.map { it.toString() }, requestType = "DASH_QUEUE_REMOVE")
        val packet = CliIpcClient.sendDashboardRequest(req, timeoutMs = 3000)
        (packet as? CliIpcPacket.DashActionResult) ?: CliIpcPacket.DashActionResult(false, "Failed to remove items via main app.")
    }
}

class StandaloneClusterBackend : CliClusterBackend {
    override suspend fun fetchClusterState(highlightBattery: Boolean, force: Boolean): CliClusterState =
        CliClusterEngine.fetchClusterState(highlightBattery, force)

    override suspend fun sendLocalPaths(paths: List<String>, deviceIds: List<String>): CliIpcPacket.DashActionResult =
        CliClusterEngine.sendLocalPaths(paths, deviceIds)

    override suspend fun listRemoteFiles(deviceId: String, path: String): CliIpcPacket.DashRemoteList =
        CliClusterEngine.listRemoteFiles(deviceId, path)

    override suspend fun retrieveRemoteItem(deviceId: String, remotePath: String, isDirectory: Boolean): CliIpcPacket.DashActionResult =
        CliClusterEngine.retrieveRemoteItem(deviceId, remotePath, isDirectory)

    override suspend fun sendClipboard(deviceId: String): CliIpcPacket.DashActionResult =
        CliClusterEngine.sendClipboard(deviceId)

    override suspend fun pullClipboard(deviceId: String): CliIpcPacket.DashClipPullResult =
        CliClusterEngine.pullClipboard(deviceId)

    override suspend fun getQueue(): List<CliQueueItem> =
        CliClusterEngine.getQueue()

    override suspend fun removeFromQueue(indices: List<Int>): CliIpcPacket.DashActionResult =
        CliClusterEngine.removeFromQueue(indices)
}
