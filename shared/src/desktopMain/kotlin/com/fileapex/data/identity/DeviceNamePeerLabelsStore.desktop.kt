package com.fileapex.data.identity

import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

private const val PURPOSE = "peer-labels"

/** Tests point this at a temp folder. */
@Volatile
internal var peerLabelsDirectory: (() -> File)? = null

private fun labelsFile(): File =
    File(peerLabelsDirectory?.invoke() ?: DesktopPlatformPaths.applicationSupportDirectory(), "device_name_peer_labels.json")

actual fun readPeerDeviceNameLabels(): StoredPeerLabels {
    val file = labelsFile()
    if (!file.isFile) return StoredPeerLabels(emptyList(), true)
    val stored = runCatching { file.readText() }.getOrDefault("")
    if (stored.isEmpty()) return StoredPeerLabels(emptyList(), true)
    val plain = SealedText.open(PURPOSE, stored) ?: return StoredPeerLabels(emptyList(), false)
    val labels = DeviceNamePeerLabelsStore.decode(plain)
    if (!stored.startsWith(FieldSealer.PREFIX)) persistPeerDeviceNameLabels(labels)
    return StoredPeerLabels(labels, true)
}

actual fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) {
    labelsFile().parentFile?.mkdirs()
    SealedText.writeFile(labelsFile(), PURPOSE, DeviceNamePeerLabelsStore.encode(labels))
}
