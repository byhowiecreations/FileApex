package com.fileapex.data.identity

import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

private fun labelsFile(): File =
    File(DesktopPlatformPaths.applicationSupportDirectory(), "device_name_peer_labels.json")

actual fun readPeerDeviceNameLabels(): List<PeerDeviceNameLabel> {
    val file = labelsFile()
    if (!file.isFile) return emptyList()
    return decodePeerDeviceNameLabels(runCatching { file.readText() }.getOrDefault(""))
}

actual fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) {
    DesktopPlatformPaths.applicationSupportDirectory()
    labelsFile().writeText(encodePeerDeviceNameLabels(labels))
}

private fun decodePeerDeviceNameLabels(raw: String) = DeviceNamePeerLabelsStore.decode(raw)

private fun encodePeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) =
    DeviceNamePeerLabelsStore.encode(labels)
