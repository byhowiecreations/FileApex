package com.omninode.data.identity

/**
 * Stable local node identity. Pairing keys off deviceId so reconnects survive IP changes.
 */
data class LocalIdentity(
    val deviceId: String,
    val deviceName: String,
    val rootPath: String,
    val sharePort: Int = DEFAULT_SHARE_PORT
) {
    companion object {
        const val DEFAULT_SHARE_PORT = 8080
        const val LOCAL_DEVICE_ID = "local-this-device"
    }
}

expect fun loadLocalIdentity(): LocalIdentity

/** Persist a custom display name used for QR / identity / cluster broadcasts. */
expect fun updateLocalDeviceName(newName: String)
