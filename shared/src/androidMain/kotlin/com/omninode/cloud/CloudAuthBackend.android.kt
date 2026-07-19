package com.omninode.cloud

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.omninode.shared.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

actual object CloudAuthBackend {
    actual fun isConfigured(): Boolean = googleWebClientId().isNotBlank()

    actual suspend fun signInWithGoogleIdToken(idToken: String): GoogleAuthSession {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = FirebaseAuth.getInstance().signInWithCredential(credential).await()
        val user = result.user ?: error("Firebase Auth returned no user")
        return GoogleAuthSession(
            firebaseUid = user.uid,
            email = user.email.orEmpty(),
            displayName = user.displayName.orEmpty()
        )
    }

    actual suspend fun currentSession(): GoogleAuthSession? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return GoogleAuthSession(
            firebaseUid = user.uid,
            email = user.email.orEmpty(),
            displayName = user.displayName.orEmpty()
        )
    }

    actual suspend fun signOut() {
        FirebaseAuth.getInstance().signOut()
    }

    actual suspend fun registerDevice(uid: String, record: CloudDeviceRecord) {
        val data = mapOf(
            "deviceId" to record.deviceId,
            "deviceName" to record.deviceName,
            "lastKnownIp" to record.lastKnownIp,
            "port" to record.port,
            "publicKeyHash" to record.publicKeyHash,
            "rootPath" to record.rootPath,
            "platform" to record.platform,
            "updatedAtEpochMs" to record.updatedAtEpochMs
        )
        deviceDoc(uid, record.deviceId)
            .set(data, SetOptions.merge())
            .await()
    }

    actual suspend fun patchDevicePresence(uid: String, presence: CloudDevicePresence) {
        val fields = mapOf(
            "deviceId" to presence.deviceId,
            "lastKnownIp" to presence.lastKnownIp,
            "port" to presence.port,
            "publicKeyHash" to presence.publicKeyHash,
            "rootPath" to presence.rootPath,
            "platform" to presence.platform,
            "updatedAtEpochMs" to presence.updatedAtEpochMs
        )
        val ref = deviceDoc(uid, presence.deviceId)
        runCatching {
            ref.update(fields).await()
        }.onFailure {
            // Document missing (first heartbeat before register completed) — create without clobbering name
            // if another client already wrote one: merge omits deviceName so existing name is preserved.
            ref.set(fields, SetOptions.merge()).await()
        }
    }

    actual suspend fun patchDeviceName(
        uid: String,
        deviceId: String,
        deviceName: String,
        updatedAtEpochMs: Long
    ) {
        val fields = mapOf(
            "deviceName" to deviceName,
            "updatedAtEpochMs" to updatedAtEpochMs
        )
        val ref = deviceDoc(uid, deviceId)
        runCatching {
            ref.update(fields).await()
        }.onFailure {
            ref.set(fields, SetOptions.merge()).await()
        }
    }

    actual suspend fun deleteDevice(uid: String, deviceId: String) {
        deviceDoc(uid, deviceId).delete().await()
    }

    actual fun observeUserDevices(
        uid: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle {
        val idle = CompletableDeferred<Unit>()
        val state = ListenerState()
        val registration: ListenerRegistration = FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("devices")
            .addSnapshotListener { snapshot, error ->
                if (state.stopped) {
                    return@addSnapshotListener
                }
                if (error != null) {
                    if (!state.stopped) {
                        onError(error)
                    }
                    return@addSnapshotListener
                }
                if (state.stopped) {
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                val records = docs.map { doc ->
                    val id = doc.getString("deviceId") ?: doc.id
                    CloudDeviceRecord(
                        deviceId = id,
                        deviceName = doc.getString("deviceName").orEmpty(),
                        lastKnownIp = doc.getString("lastKnownIp").orEmpty(),
                        port = (doc.getLong("port") ?: 8080L).toInt(),
                        publicKeyHash = doc.getString("publicKeyHash").orEmpty(),
                        rootPath = doc.getString("rootPath").orEmpty(),
                        platform = doc.getString("platform").orEmpty(),
                        updatedAtEpochMs = doc.getLong("updatedAtEpochMs") ?: 0L
                    )
                }
                if (!state.stopped) {
                    onDevices(records)
                }
            }
        return object : CloudRegistryHandle {
            override fun stop() {
                if (state.stopped) {
                    return
                }
                state.stopped = true
                runCatching { registration.remove() }
                if (!idle.isCompleted) {
                    idle.complete(Unit)
                }
            }

            override suspend fun awaitIdle() {
                idle.await()
                // Let Firebase finish any in-flight native callback before Auth tear-down.
                delay(LISTENER_DRAIN_MS)
            }
        }
    }

    private fun deviceDoc(uid: String, deviceId: String) =
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("devices").document(deviceId)

    private class ListenerState {
        @Volatile
        var stopped: Boolean = false
    }

    private const val LISTENER_DRAIN_MS = 75L
}

actual fun googleWebClientId(): String = BuildConfig.GOOGLE_WEB_CLIENT_ID

actual fun firebaseApiKey(): String =
    com.google.firebase.FirebaseApp.getInstance().options.apiKey.orEmpty()

actual fun firebaseProjectId(): String =
    com.google.firebase.FirebaseApp.getInstance().options.projectId.orEmpty()

actual fun currentPlatformLabel(): String = "android"
