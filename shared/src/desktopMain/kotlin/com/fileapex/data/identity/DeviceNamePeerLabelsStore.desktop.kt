package com.fileapex.data.identity

import com.fileapex.data.seal.SealedText
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

private const val PURPOSE = "peer-labels"

private fun labelsFile(): File =
    File(DesktopPlatformPaths.applicationSupportDirectory(), "device_name_peer_labels.json")

actual fun readPeerDeviceNameLabels(): List<PeerDeviceNameLabel> {
    val file = labelsFile()
    if (!file.isFile) return emptyList()
    return decodePeerDeviceNameLabels(SealedText.readFile(file, PURPOSE))
}

actual fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) {
    DesktopPlatformPaths.applicationSupportDirectory()
    SealedText.writeFile(labelsFile(), PURPOSE, encodePeerDeviceNameLabels(labels))
}

private fun decodePeerDeviceNameLabels(raw: String) = DeviceNamePeerLabelsStore.decode(raw)

private fun encodePeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) =
    DeviceNamePeerLabelsStore.encode(labels)
