package com.omninode.cloud

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.omninode.shared.BuildConfig
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

    actual suspend fun publishDevice(uid: String, record: CloudDeviceRecord) {
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
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("devices").document(record.deviceId)
            .set(data, SetOptions.merge())
            .await()
    }

    actual suspend fun deleteDevice(uid: String, deviceId: String) {
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("devices").document(deviceId)
            .delete()
            .await()
    }

    actual fun observeUserDevices(
        uid: String,
        excludeDeviceId: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle {
        val registration: ListenerRegistration = FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("devices")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                val records = docs.mapNotNull { doc ->
                    val id = doc.getString("deviceId") ?: doc.id
                    if (id == excludeDeviceId) return@mapNotNull null
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
                onDevices(records)
            }
        return object : CloudRegistryHandle {
            override fun stop() {
                registration.remove()
            }
        }
    }
}

actual fun googleWebClientId(): String = BuildConfig.GOOGLE_WEB_CLIENT_ID

actual fun firebaseApiKey(): String =
    com.google.firebase.FirebaseApp.getInstance().options.apiKey.orEmpty()

actual fun firebaseProjectId(): String =
    com.google.firebase.FirebaseApp.getInstance().options.projectId.orEmpty()

actual fun currentPlatformLabel(): String = "android"
