package com.fileapex.cloud

import com.fileapex.cloud.diagnostics.DiagnosticsRelaySession

private fun cloudUnavailable(): Nothing =
    throw UnsupportedOperationException("Cloud account features are not part of the F-Droid build")

private object NoCloudRegistryHandle : CloudRegistryHandle {
    override fun stop() = Unit

    override suspend fun awaitIdle() = Unit
}

actual object CloudAuthBackend {
    actual fun isConfigured(): Boolean = false

    actual suspend fun signInWithGoogleIdToken(idToken: String): GoogleAuthSession = cloudUnavailable()

    actual suspend fun currentSession(): GoogleAuthSession? = null

    actual suspend fun firebaseIdToken(): String? = null

    actual suspend fun signOut() = Unit

    actual suspend fun registerDevice(uid: String, record: CloudDeviceRecord) {
        cloudUnavailable()
    }

    actual suspend fun patchDevicePresence(uid: String, presence: CloudDevicePresence) {
        cloudUnavailable()
    }

    actual suspend fun patchDeviceName(
        uid: String,
        deviceId: String,
        deviceName: String,
        updatedAtEpochMs: Long
    ) {
        cloudUnavailable()
    }

    actual suspend fun deleteDevice(uid: String, deviceId: String) {
        cloudUnavailable()
    }

    actual suspend fun patchDeviceFcmToken(uid: String, deviceId: String, fcmToken: String) {
        cloudUnavailable()
    }

    actual suspend fun patchDeviceClipboardPublicKey(
        uid: String,
        deviceId: String,
        clipboardPublicKey: String
    ) {
        cloudUnavailable()
    }

    actual suspend fun patchDeviceDiagnosticsCloud(
        uid: String,
        deviceId: String,
        diagnosticsPublicKey: String,
        deviceDetailsCloudEnabled: Boolean
    ) {
        cloudUnavailable()
    }

    actual suspend fun upsertDiagnosticsRelaySession(uid: String, session: DiagnosticsRelaySession) {
        cloudUnavailable()
    }

    actual suspend fun fetchDiagnosticsRelaySession(
        uid: String,
        sessionId: String
    ): DiagnosticsRelaySession? = cloudUnavailable()

    actual suspend fun completeDiagnosticsRelaySession(
        uid: String,
        sessionId: String,
        responseEncPayload: String
    ) {
        cloudUnavailable()
    }

    actual suspend fun deleteDiagnosticsRelaySession(uid: String, sessionId: String) {
        cloudUnavailable()
    }

    actual suspend fun failDiagnosticsRelaySession(uid: String, sessionId: String) {
        cloudUnavailable()
    }

    actual suspend fun fetchCloudDeviceRecord(uid: String, deviceId: String): CloudDeviceRecord? =
        cloudUnavailable()

    actual suspend fun fetchAllUserDevices(uid: String): List<CloudDeviceRecord> = cloudUnavailable()

    actual fun observeDiagnosticsRelayInbox(
        uid: String,
        responderDeviceId: String,
        onSession: (DiagnosticsRelaySession) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle = NoCloudRegistryHandle

    actual fun observeUserDevices(
        uid: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle = NoCloudRegistryHandle
}

actual fun googleWebClientId(): String = ""

actual fun firebaseApiKey(): String = ""

actual fun firebaseProjectId(): String = ""
