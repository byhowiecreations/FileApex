package com.fileapex.data.identity

/**
 * Stable local node identity. Pairing keys off deviceId so reconnects survive IP changes.
 */
data class LocalIdentity(
    val deviceId: String,
    val deviceName: String,
    val rootPath: String,
    val sharePort: Int = DEFAULT_SHARE_PORT,
    /** The id this device had before its key changed and the id followed it; empty when it never changed or the change was settled. */
    val previousDeviceId: String = ""
) {
    companion object {
        const val DEFAULT_SHARE_PORT = 8080
        const val LOCAL_DEVICE_ID = "local-this-device"
    }
}

expect fun loadLocalIdentity(): LocalIdentity

/** Persist a custom display name used for QR / identity / cluster broadcasts. */
expect fun updateLocalDeviceName(newName: String)

/** Forgets [LocalIdentity.previousDeviceId] once the cluster has taken the new id, or when this device leaves it. */
expect fun clearPreviousDeviceId()
