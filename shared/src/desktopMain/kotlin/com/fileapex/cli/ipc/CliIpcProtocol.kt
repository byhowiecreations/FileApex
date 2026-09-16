package com.fileapex.cli.ipc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CLI_IPC_PORT = 49429

@Serializable
data class CliRequest(
    val args: List<String> = emptyList(),
    val cwd: String = "",
    val requestType: String = "COMMAND",
    val payload: String = ""
)

@Serializable
data class CliActiveTransfer(
    val deviceId: String = "",
    val deviceName: String = "",
    val fileName: String = "",
    val progress: Float = 0f,
    val speed: String = "",
    val eta: String = "",
    val bytesFormatted: String = ""
)

@Serializable
data class CliClusterState(
    val onlinePeerCount: Int,
    val totalPeerCount: Int,
    val activeTransfersCount: Int,
    val queueCount: Int,
    val activeTransferProgress: Float? = null,
    val activeTransferSpeed: String = "",
    val activeTransferEta: String = "",
    val activeTransferSummary: String = "",
    val activeTransferBytesFormatted: String = "",
    val activeTransferLabel: String = "",
    val activeTransfers: List<CliActiveTransfer> = emptyList(),
    val devices: List<CliDeviceStatus> = emptyList()
)

@Serializable
data class CliDeviceStatus(
    val id: Int,
    val deviceId: String,
    val deviceName: String,
    val slugOrAlias: String,
    val batteryBlocks: String,
    val batteryPercent: Int? = null,
    val chargingState: String = "",
    val clipboardStatus: String,
    val status: String,
    val host: String = "",
    val port: Int = 0
)

@Serializable
data class CliRemoteFile(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

@Serializable
data class CliQueueItem(
    val index: Int,
    val id: String,
    val sourceSummary: String,
    val targetDevices: String,
    val lastError: String? = null
)

@Serializable
sealed class CliIpcPacket {
    @Serializable
    @SerialName("stdout")
    data class Stdout(val text: String) : CliIpcPacket()

    @Serializable
    @SerialName("stderr")
    data class Stderr(val text: String) : CliIpcPacket()

    @Serializable
    @SerialName("progress")
    data class Progress(
        val percent: Float,
        val speed: String,
        val eta: String,
        val sentBytes: Long,
        val totalBytes: Long
    ) : CliIpcPacket()

    @Serializable
    @SerialName("prompt")
    data class Prompt(val question: String) : CliIpcPacket()

    @Serializable
    @SerialName("input")
    data class Input(val text: String) : CliIpcPacket()

    @Serializable
    @SerialName("exit")
    data class Exit(val code: Int) : CliIpcPacket()

    @Serializable
    @SerialName("dash_state")
    data class DashState(val state: CliClusterState) : CliIpcPacket()

    @Serializable
    @SerialName("dash_remote_list")
    data class DashRemoteList(val currentPath: String, val items: List<CliRemoteFile>) : CliIpcPacket()

    @Serializable
    @SerialName("dash_clip_pull_result")
    data class DashClipPullResult(val content: String?, val error: String? = null) : CliIpcPacket()

    @Serializable
    @SerialName("dash_queue_list")
    data class DashQueueList(val items: List<CliQueueItem>) : CliIpcPacket()

    @Serializable
    @SerialName("dash_action_result")
    data class DashActionResult(val success: Boolean, val message: String) : CliIpcPacket()
}
