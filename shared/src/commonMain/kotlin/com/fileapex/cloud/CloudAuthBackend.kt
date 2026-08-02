package com.fileapex.cloud

/**
 * Platform Firebase/Auth + Firestore registry (virtual device directory only).
 */
expect object CloudAuthBackend {
    fun isConfigured(): Boolean

    suspend fun signInWithGoogleIdToken(idToken: String): GoogleAuthSession

    suspend fun currentSession(): GoogleAuthSession?

    suspend fun signOut()

    /**
     * First-time (or recovery) upsert of the full device document, including [CloudDeviceRecord.deviceName].
     * Must not be used from heartbeats or other lifecycle loops.
     */
    suspend fun registerDevice(uid: String, record: CloudDeviceRecord)

    /**
     * Partial field update for LAN/presence only — never writes [deviceName].
     */
    suspend fun patchDevicePresence(uid: String, presence: CloudDevicePresence)

    /**
     * Partial field update for display name only — caller must be an explicit user rename action.
     */
    suspend fun patchDeviceName(uid: String, deviceId: String, deviceName: String, updatedAtEpochMs: Long)

    suspend fun deleteDevice(uid: String, deviceId: String)

    /** Patches the Android FCM token for silent background wake — never touches [deviceName]. */
    suspend fun patchDeviceFcmToken(uid: String, deviceId: String, fcmToken: String)

    /**
     * Start listening / polling the user device collection (includes this device’s document).
     * Remote snapshots seed peer devices into the local repository.
     * This device's own [CloudDeviceRecord.deviceName] is published only via
     * [GoogleLinkCoordinator.publishUserRenamedDevice] / initial register — never imported
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

const val OAUTH_REDIRECT_URI = "apex://oauth-callback"

/** Fixed loopback port/path — must match Web client Authorized redirect URI in Google Cloud Console. */
const val DESKTOP_OAUTH_LOOPBACK_PORT = 8765
const val DESKTOP_OAUTH_LOOPBACK_REDIRECT_URI = "http://127.0.0.1:8765/callback"
