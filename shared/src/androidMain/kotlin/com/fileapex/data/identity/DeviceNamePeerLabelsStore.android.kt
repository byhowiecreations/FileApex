package com.fileapex.data.identity

import android.content.Context

private const val PREFS = "fileapex_device_name_peer_labels"
private const val KEY_JSON = "labels_json"

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
    return decodePeerDeviceNameLabels(raw)
}

private fun decodePeerDeviceNameLabels(raw: String) = DeviceNamePeerLabelsStore.decode(raw)

private fun encodePeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) =
    DeviceNamePeerLabelsStore.encode(labels)

actual fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>) {
    if (!::labelsContext.isInitialized) return
    labelsContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_JSON, encodePeerDeviceNameLabels(labels))
        .apply()
}
