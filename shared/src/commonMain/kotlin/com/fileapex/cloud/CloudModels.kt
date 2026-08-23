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
    val fcmToken: String = "",
    /** Base64 X25519 public key for encrypted diagnostics relay — optional. */
    val diagnosticsPublicKey: String = "",
    /** Base64 X25519 public key for encrypted clipboard payloads. */
    val clipboardPublicKey: String = "",
    /** Peer opted in to encrypted cloud Device Details when LAN is unavailable. */
    val deviceDetailsCloudEnabled: Boolean = false,
    /** Static hardware parameters (manufacturer, model, device, board) for reconciliation. */
    val hardwareFingerprint: Map<String, String> = emptyMap()
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
    val updatedAtEpochMs: Long,
    val hardwareFingerprint: Map<String, String> = emptyMap()
)

data class GoogleAuthSession(
    val firebaseUid: String,
    val email: String,
    val displayName: String
)
