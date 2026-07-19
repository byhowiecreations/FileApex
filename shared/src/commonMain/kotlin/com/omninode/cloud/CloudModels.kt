package com.omninode.cloud

/**
 * Privacy-safe peer advertisement published to Firestore.
 * Never includes file paths beyond the shared browse root marker, nor file contents.
 */
data class CloudDeviceRecord(
    val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String,
    val rootPath: String,
    val platform: String,
    val updatedAtEpochMs: Long
)

data class GoogleAuthSession(
    val firebaseUid: String,
    val email: String,
    val displayName: String
)
