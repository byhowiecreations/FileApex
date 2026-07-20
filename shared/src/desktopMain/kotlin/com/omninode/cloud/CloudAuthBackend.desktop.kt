package com.omninode.cloud

import com.omninode.di.OmniNodeServices
import com.omninode.network.OmniHttpClientFactory
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

actual object CloudAuthBackend {
    private val prefs = Preferences.userRoot().node("com.omninode.firebase")
    private val client get() = OmniNodeServices.httpClient
    private val desktopJson get() = OmniHttpClientFactory.defaultJson
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    actual fun isConfigured(): Boolean =
        googleWebClientId().isNotBlank() && firebaseApiKey().isNotBlank()

    actual suspend fun signInWithGoogleIdToken(idToken: String): GoogleAuthSession {
        val apiKey = firebaseApiKey()
        val response = client.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(
                SignInWithIdpRequest(
                    postBody = "id_token=$idToken&providerId=google.com",
                    requestUri = OAUTH_REDIRECT_URI,
                    returnIdpCredential = true,
                    returnSecureToken = true
                )
            )
        }
        if (!response.status.isSuccess()) {
            error("Firebase sign-in failed (${response.status}): ${response.bodyAsText()}")
        }
        val body = response.body<SignInWithIdpResponse>()
        val idTokenOut = body.idToken ?: error("Firebase response missing idToken")
        val refresh = body.refreshToken.orEmpty()
        val uid = body.localId ?: error("Firebase response missing localId")
        prefs.put(KEY_ID_TOKEN, idTokenOut)
        prefs.put(KEY_REFRESH_TOKEN, refresh)
        prefs.put(KEY_UID, uid)
        prefs.put(KEY_EMAIL, body.email.orEmpty())
        prefs.put(KEY_DISPLAY_NAME, body.displayName.orEmpty())
        return GoogleAuthSession(
            firebaseUid = uid,
            email = body.email.orEmpty(),
            displayName = body.displayName.orEmpty()
        )
    }

    actual suspend fun currentSession(): GoogleAuthSession? {
        val uid = prefs.get(KEY_UID, "")
        val refresh = prefs.get(KEY_REFRESH_TOKEN, "")
        if (uid.isBlank() || refresh.isBlank()) return null
        runCatching { refreshIdTokenIfNeeded() }
        return GoogleAuthSession(
            firebaseUid = uid,
            email = prefs.get(KEY_EMAIL, ""),
            displayName = prefs.get(KEY_DISPLAY_NAME, "")
        )
    }

    actual suspend fun signOut() {
        prefs.remove(KEY_ID_TOKEN)
        prefs.remove(KEY_REFRESH_TOKEN)
        prefs.remove(KEY_UID)
        prefs.remove(KEY_EMAIL)
        prefs.remove(KEY_DISPLAY_NAME)
    }

    actual suspend fun registerDevice(uid: String, record: CloudDeviceRecord) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val parent =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices"
        val body = firestoreDocumentBody(
            deviceId = record.deviceId,
            deviceName = record.deviceName,
            lastKnownIp = record.lastKnownIp,
            port = record.port,
            publicKeyHash = record.publicKeyHash,
            rootPath = record.rootPath,
            platform = record.platform,
            updatedAtEpochMs = record.updatedAtEpochMs
        )
        patchOrCreateDocument(
            token = token,
            parent = parent,
            deviceId = record.deviceId,
            body = body,
            fieldPaths = listOf(
                "deviceId",
                "deviceName",
                "lastKnownIp",
                "port",
                "publicKeyHash",
                "rootPath",
                "platform",
                "updatedAtEpochMs"
            )
        )
    }

    actual suspend fun patchDevicePresence(uid: String, presence: CloudDevicePresence) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val parent =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices"
        val body = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put("deviceId", buildJsonObject { put("stringValue", presence.deviceId) })
                    put("lastKnownIp", buildJsonObject { put("stringValue", presence.lastKnownIp) })
                    put("port", buildJsonObject { put("integerValue", presence.port.toString()) })
                    put(
                        "publicKeyHash",
                        buildJsonObject { put("stringValue", presence.publicKeyHash) }
                    )
                    put("rootPath", buildJsonObject { put("stringValue", presence.rootPath) })
                    put("platform", buildJsonObject { put("stringValue", presence.platform) })
                    put(
                        "updatedAtEpochMs",
                        buildJsonObject {
                            put("integerValue", presence.updatedAtEpochMs.toString())
                        }
                    )
                }
            )
        }
        patchOrCreateDocument(
            token = token,
            parent = parent,
            deviceId = presence.deviceId,
            body = body,
            fieldPaths = listOf(
                "deviceId",
                "lastKnownIp",
                "port",
                "publicKeyHash",
                "rootPath",
                "platform",
                "updatedAtEpochMs"
            )
        )
    }

    actual suspend fun patchDeviceName(
        uid: String,
        deviceId: String,
        deviceName: String,
        updatedAtEpochMs: Long
    ) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val parent =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices"
        val body = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put("deviceName", buildJsonObject { put("stringValue", deviceName) })
                    put(
                        "updatedAtEpochMs",
                        buildJsonObject { put("integerValue", updatedAtEpochMs.toString()) }
                    )
                }
            )
        }
        patchOrCreateDocument(
            token = token,
            parent = parent,
            deviceId = deviceId,
            body = body,
            fieldPaths = listOf("deviceName", "updatedAtEpochMs")
        )
    }

    actual suspend fun deleteDevice(uid: String, deviceId: String) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val url =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices/$deviceId"
        client.delete(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    actual fun observeUserDevices(
        uid: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle {
        val idle = CompletableDeferred<Unit>()
        val state = ListenerState()
        val job = pollScope.launch {
            try {
                while (isActive && !state.stopped) {
                    runCatching {
                        val token = requireIdToken()
                        val project = firebaseProjectId()
                        val url =
                            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                                "users/$uid/devices"
                        val response = client.get(url) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        if (!response.status.isSuccess()) {
                            error("Firestore list failed (${response.status}): ${response.bodyAsText()}")
                        }
                        val body = desktopJson.parseToJsonElement(response.bodyAsText()).jsonObject
                        val docsEl = body["documents"]
                        val list = mutableListOf<CloudDeviceRecord>()
                        val arr = docsEl as? kotlinx.serialization.json.JsonArray
                        arr?.forEach { el ->
                            val doc = el.jsonObject
                            val fields = doc["fields"]?.jsonObject ?: return@forEach
                            val id = stringField(fields, "deviceId")
                                ?: doc["name"]?.jsonPrimitive?.contentOrNull
                                    ?.substringAfterLast('/')
                                ?: return@forEach
                            list += CloudDeviceRecord(
                                deviceId = id,
                                deviceName = stringField(fields, "deviceName").orEmpty(),
                                lastKnownIp = stringField(fields, "lastKnownIp").orEmpty(),
                                port = integerField(fields, "port")?.toInt() ?: 8080,
                                publicKeyHash = stringField(fields, "publicKeyHash").orEmpty(),
                                rootPath = stringField(fields, "rootPath").orEmpty(),
                                platform = stringField(fields, "platform").orEmpty(),
                                updatedAtEpochMs = integerField(fields, "updatedAtEpochMs") ?: 0L
                            )
                        }
                        if (!state.stopped) {
                            onDevices(list)
                        }
                    }.onFailure { error ->
                        if (!state.stopped) {
                            onError(error)
                        }
                    }
                    if (state.stopped) break
                    delay(POLL_MS)
                }
            } finally {
                if (!idle.isCompleted) {
                    idle.complete(Unit)
                }
            }
        }
        return object : CloudRegistryHandle {
            override fun stop() {
                if (state.stopped) {
                    return
                }
                state.stopped = true
                job.cancel()
            }

            override suspend fun awaitIdle() {
                idle.await()
            }
        }
    }

    private class ListenerState {
        @Volatile
        var stopped: Boolean = false
    }

    private suspend fun patchOrCreateDocument(
        token: String,
        parent: String,
        deviceId: String,
        body: JsonObject,
        fieldPaths: List<String>
    ) {
        val patchUrl = "$parent/$deviceId"
        val patch = client.patch(patchUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("currentDocument.exists", "true")
            fieldPaths.forEach { path ->
                parameter("updateMask.fieldPaths", path)
            }
            setBody(body)
        }
        if (patch.status.isSuccess()) return
        val create = client.post(parent) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("documentId", deviceId)
            setBody(body)
        }
        if (!create.status.isSuccess()) {
            error("Firestore write failed (${create.status}): ${create.bodyAsText()}")
        }
    }

    private fun firestoreDocumentBody(
        deviceId: String,
        deviceName: String,
        lastKnownIp: String,
        port: Int,
        publicKeyHash: String,
        rootPath: String,
        platform: String,
        updatedAtEpochMs: Long
    ): JsonObject = buildJsonObject {
        put(
            "fields",
            buildJsonObject {
                put("deviceId", buildJsonObject { put("stringValue", deviceId) })
                put("deviceName", buildJsonObject { put("stringValue", deviceName) })
                put("lastKnownIp", buildJsonObject { put("stringValue", lastKnownIp) })
                put("port", buildJsonObject { put("integerValue", port.toString()) })
                put("publicKeyHash", buildJsonObject { put("stringValue", publicKeyHash) })
                put("rootPath", buildJsonObject { put("stringValue", rootPath) })
                put("platform", buildJsonObject { put("stringValue", platform) })
                put(
                    "updatedAtEpochMs",
                    buildJsonObject { put("integerValue", updatedAtEpochMs.toString()) }
                )
            }
        )
    }

    private suspend fun requireIdToken(): String {
        refreshIdTokenIfNeeded()
        return prefs.get(KEY_ID_TOKEN, "").ifBlank { error("Not signed in to Firebase") }
    }

    private suspend fun refreshIdTokenIfNeeded() {
        val refresh = prefs.get(KEY_REFRESH_TOKEN, "")
        if (refresh.isBlank()) return
        val apiKey = firebaseApiKey()
        val response = client.post(
            "https://securetoken.googleapis.com/v1/token?key=$apiKey"
        ) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("grant_type=refresh_token&refresh_token=$refresh")
        }
        if (!response.status.isSuccess()) return
        val body = response.bodyAsText()
        val obj = desktopJson.parseToJsonElement(body).jsonObject
        val idToken = obj["id_token"]?.jsonPrimitive?.contentOrNull
            ?: obj["access_token"]?.jsonPrimitive?.contentOrNull
        if (!idToken.isNullOrBlank()) {
            prefs.put(KEY_ID_TOKEN, idToken)
        }
        obj["refresh_token"]?.jsonPrimitive?.contentOrNull?.let {
            prefs.put(KEY_REFRESH_TOKEN, it)
        }
    }

    private fun stringField(fields: JsonObject, name: String): String? =
        fields[name]?.jsonObject?.get("stringValue")?.jsonPrimitive?.contentOrNull

    private fun integerField(fields: JsonObject, name: String): Long? =
        fields[name]?.jsonObject?.get("integerValue")?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private const val KEY_ID_TOKEN = "id_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_UID = "uid"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val POLL_MS = 12_000L
}

@Serializable
private data class SignInWithIdpRequest(
    val postBody: String,
    val requestUri: String,
    val returnIdpCredential: Boolean,
    val returnSecureToken: Boolean
)

@Serializable
private data class SignInWithIdpResponse(
    val idToken: String? = null,
    val refreshToken: String? = null,
    val localId: String? = null,
    val email: String? = null,
    @SerialName("displayName") val displayName: String? = null
)
