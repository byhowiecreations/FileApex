package com.fileapex.cloud

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
    val clientVersion: String,
    val clientVersionCode: Int,
    val updatedAtEpochMs: Long,
    /** Android FCM registration token for silent background wake (Path A). */
    val fcmToken: String = ""
)

/**
 * Presence / connectivity fields only. Never includes [deviceName] so heartbeats cannot
 * roll back an explicit user rename.
 */
data class CloudDevicePresence(
    val deviceId: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String,
    val rootPath: String,
    val platform: String,
    val clientVersion: String,
    val clientVersionCode: Int,
    val updatedAtEpochMs: Long
)

data class GoogleAuthSession(
    val firebaseUid: String,
    val email: String,
    val displayName: String
)
