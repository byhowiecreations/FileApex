package com.fileapex.data.identity

import android.content.Context
import com.fileapex.data.seal.SealedText

private const val PREFS = "fileapex_device_name_peer_labels"
private const val KEY_JSON = "labels_json"
private const val PURPOSE = "peer-labels"

private lateinit var labelsContext: Context

fun initAndroidDeviceNamePeerLabels(context: Context) {
    labelsContext = context.applicationContext
}

actual fun readPeerDeviceNameLabels(): List<PeerDeviceNameLabel> {
    if (!::labelsContext.isInitialized) return emptyList()
    val raw = labelsContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_JSON, null)
        .orEmpty()
    val plain = SealedText.open(PURPOSE, raw) ?: return emptyList()
    if (raw.isNotEmpty() && !raw.startsWith("fe1:")) persistPeerDeviceNameLabels(decodePeerDeviceNameLabels(plain))
    return decodePeerDeviceNameLabels(plain)
}

private fun decodePeerDeviceNameLabels(raw: String) = DeviceNamePeerLabelsStore.decode(raw)

private fun encodePeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) =
    DeviceNamePeerLabelsStore.encode(labels)

actual fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) {
    if (!::labelsContext.isInitialized) return
    val sealed = SealedText.seal(PURPOSE, encodePeerDeviceNameLabels(labels)) ?: return
    labelsContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_JSON, sealed)
        .apply()
}
