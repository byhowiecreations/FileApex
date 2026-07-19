package com.omninode.cloud

/**
 * Platform Firebase/Auth + Firestore registry (virtual device directory only).
 */
expect object CloudAuthBackend {
    fun isConfigured(): Boolean

    suspend fun signInWithGoogleIdToken(idToken: String): GoogleAuthSession

    suspend fun currentSession(): GoogleAuthSession?

    suspend fun signOut()

    /**
     * Upsert this device under `users/{uid}/devices/{deviceId}`.
     */
    suspend fun publishDevice(uid: String, record: CloudDeviceRecord)

    suspend fun deleteDevice(uid: String, deviceId: String)

    /**
     * Start listening / polling the user device collection.
     * [onDevices] receives every device except [excludeDeviceId] when possible.
     * @return a handle that stops the listener when [CloudRegistryHandle.stop] is called.
     */
    fun observeUserDevices(
        uid: String,
        excludeDeviceId: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle
}

interface CloudRegistryHandle {
    fun stop()
}

expect fun googleWebClientId(): String

expect fun firebaseApiKey(): String

expect fun firebaseProjectId(): String

const val OAUTH_REDIRECT_URI = "omni://oauth-callback"
