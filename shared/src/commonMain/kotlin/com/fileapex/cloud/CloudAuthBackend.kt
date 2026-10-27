package com.fileapex.cloud

import com.fileapex.cloud.diagnostics.DiagnosticsRelaySession

/**
 * Platform Firebase/Auth + Firestore registry (virtual device directory only).
 */
expect object CloudAuthBackend {
    fun isConfigured(): Boolean

    suspend fun signInWithGoogleIdToken(idToken: String): GoogleAuthSession

    suspend fun currentSession(): GoogleAuthSession?

    /** A current Firebase Auth ID token for the signed-in user, or null when not signed in. Sent to the FCM relay. [forceRefresh] asks Firebase for a new one, which is how a disabled device user is found out. */
    suspend fun firebaseIdToken(forceRefresh: Boolean = false): String?

    /**
     * Swaps the Google session for this device's own Firebase user (a custom token from the relay's enrol call).
     * The session keeps reporting the Google account's uid, because every Firestore path is under it.
     */
    suspend fun adoptDeviceToken(customToken: String)

    suspend fun signOut()

    /**
     * First-time (or recovery) upsert of the full device document, including [CloudDeviceRecord.deviceName].
     * Must not be used from heartbeats or other lifecycle loops.
     */
    suspend fun registerDevice(uid: String, record: CloudDeviceRecord)

    /**
     * Partial field update for LAN/presence only, never writes [deviceName].
     */
    suspend fun patchDevicePresence(uid: String, presence: CloudDevicePresence)

    /**
     * Partial field update for display name only, caller must be an explicit user rename action.
     */
    suspend fun patchDeviceName(uid: String, deviceId: String, deviceName: String, updatedAtEpochMs: Long, sealed: Map<String, String> = emptyMap())

    suspend fun deleteDevice(uid: String, deviceId: String)

    /** Patches the Android FCM token for silent background wake, never touches [deviceName]. */
    suspend fun patchDeviceFcmToken(uid: String, deviceId: String, fcmToken: String)

    suspend fun patchDeviceClipboardPublicKey(uid: String, deviceId: String, clipboardPublicKey: String)

    /**
     * Patches diagnostics relay opt-in + public key, never part of presence heartbeats.
     */
    suspend fun patchDeviceDiagnosticsCloud(
        uid: String,
        deviceId: String,
        diagnosticsPublicKey: String,
        deviceDetailsCloudEnabled: Boolean
    )

    suspend fun upsertDiagnosticsRelaySession(uid: String, session: DiagnosticsRelaySession)

    suspend fun fetchDiagnosticsRelaySession(uid: String, sessionId: String): DiagnosticsRelaySession?

    suspend fun completeDiagnosticsRelaySession(
        uid: String,
        sessionId: String,
        responseEncPayload: String
    )

    suspend fun deleteDiagnosticsRelaySession(uid: String, sessionId: String)

    suspend fun failDiagnosticsRelaySession(uid: String, sessionId: String)

    /** Fresh read of a cloud registry device row (bypasses in-memory cache). */
    suspend fun fetchCloudDeviceRecord(uid: String, deviceId: String): CloudDeviceRecord?

    /** Fetches all user device records from Firestore for reconciliation and stale pruning. */
    suspend fun fetchAllUserDevices(uid: String): List<CloudDeviceRecord>

    /**
     * Observes pending diagnostics relay requests targeting [responderDeviceId].
     */
    fun observeDiagnosticsRelayInbox(
        uid: String,
        responderDeviceId: String,
        onSession: (DiagnosticsRelaySession) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle

    /**
     * Start listening / polling the user device collection (includes this device’s document).
     * Remote snapshots seed peer devices into the local repository.
     * This device's own [CloudDeviceRecord.deviceName] is published only via
     * [GoogleLinkCoordinator.publishUserRenamedDevice] / initial register, never imported
     * back onto the local identity from a snapshot.
     * @return a handle that stops the listener when [CloudRegistryHandle.stop] is called.
     */
    fun observeUserDevices(
        uid: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle
}

/**
 * Firestore registry subscription. Always [stop] then [awaitIdle] before Auth sign-out or
 * opening a replacement listener so Firebase’s local persistence/mutexes are not used after teardown.
 */
interface CloudRegistryHandle {
    /** Marks the subscription stopped and begins detach; may return while callbacks finish. */
    fun stop()

    /** Suspends until the subscription has fully drained (no further callbacks will run). */
    suspend fun awaitIdle()
}

expect fun googleWebClientId(): String

expect fun firebaseApiKey(): String

expect fun firebaseProjectId(): String

/** Fixed loopback port/path, must match Web client Authorized redirect URI in Google Cloud Console. */
const val DESKTOP_OAUTH_LOOPBACK_PORT = 8765
const val DESKTOP_OAUTH_LOOPBACK_REDIRECT_URI = "http://127.0.0.1:8765/callback"
