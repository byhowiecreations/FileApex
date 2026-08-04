package com.fileapex.cloud

import com.fileapex.cloud.diagnostics.DiagnosticsRelaySession
import com.fileapex.cloud.diagnostics.DiagnosticsRelayStatus
import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexHttpClientFactory
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

actual object CloudAuthBackend {
    private val prefs = Preferences.userRoot().node("com.fileapex.firebase")
    private val client get() = FileApexServices.httpClient
    private val desktopJson get() = FileApexHttpClientFactory.defaultJson
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
                    requestUri = DesktopAuthCoordinator.oauthRedirectUriForFirebase(),
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
            clientVersion = record.clientVersion,
            clientVersionCode = record.clientVersionCode,
            updatedAtEpochMs = record.updatedAtEpochMs
        )
        patchOrCreateDocument(
            token = token,
            parent = parent,
            documentId = record.deviceId,
            body = body,
            fieldPaths = listOf(
                "deviceId",
                "deviceName",
                "lastKnownIp",
                "port",
                "publicKeyHash",
                "rootPath",
                "platform",
                "clientVersion",
                "clientVersionCode",
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
                    put("clientVersion", buildJsonObject { put("stringValue", presence.clientVersion) })
                    put(
                        "clientVersionCode",
                        buildJsonObject { put("integerValue", presence.clientVersionCode.toString()) }
                    )
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
            documentId = presence.deviceId,
            body = body,
            fieldPaths = listOf(
                "deviceId",
                "lastKnownIp",
                "port",
                "publicKeyHash",
                "rootPath",
                "platform",
                "clientVersion",
                "clientVersionCode",
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
            documentId = deviceId,
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

    actual suspend fun patchDeviceFcmToken(uid: String, deviceId: String, fcmToken: String) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val parent =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices"
        val body = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put("fcmToken", buildJsonObject { put("stringValue", fcmToken.trim()) })
                }
            )
        }
        patchOrCreateDocument(
            token = token,
            parent = parent,
            documentId = deviceId,
            body = body,
            fieldPaths = listOf("fcmToken")
        )
    }

    actual suspend fun patchDeviceDiagnosticsCloud(
        uid: String,
        deviceId: String,
        diagnosticsPublicKey: String,
        deviceDetailsCloudEnabled: Boolean
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
                    put(
                        "diagnosticsPublicKey",
                        buildJsonObject { put("stringValue", diagnosticsPublicKey.trim()) }
                    )
                    put(
                        "deviceDetailsCloudEnabled",
                        buildJsonObject { put("booleanValue", deviceDetailsCloudEnabled) }
                    )
                }
            )
        }
        patchOrCreateDocument(
            token = token,
            parent = parent,
            documentId = deviceId,
            body = body,
            fieldPaths = listOf("diagnosticsPublicKey", "deviceDetailsCloudEnabled")
        )
    }

    actual suspend fun upsertDiagnosticsRelaySession(uid: String, session: DiagnosticsRelaySession) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val parent =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/diagnosticsRelay"
        val body = relaySessionDocumentBody(session)
        patchOrCreateDocument(
            token = token,
            parent = parent,
            documentId = session.sessionId,
            body = body,
            fieldPaths = relaySessionFieldPaths()
        )
    }

    actual suspend fun fetchDiagnosticsRelaySession(
        uid: String,
        sessionId: String
    ): DiagnosticsRelaySession? {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val url =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/diagnosticsRelay/$sessionId"
        val response = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            error("Firestore relay fetch failed (${response.status}): ${response.bodyAsText()}")
        }
        val doc = desktopJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return parseRelaySessionDocument(doc)
    }

    actual suspend fun completeDiagnosticsRelaySession(
        uid: String,
        sessionId: String,
        responseEncPayload: String
    ) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val url =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/diagnosticsRelay/$sessionId"
        val body = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put(
                        "responseEncPayload",
                        buildJsonObject { put("stringValue", responseEncPayload) }
                    )
                    put(
                        "status",
                        buildJsonObject { put("stringValue", DiagnosticsRelayStatus.COMPLETE) }
                    )
                }
            )
        }
        val patch = client.patch(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("currentDocument.exists", "true")
            parameter("updateMask.fieldPaths", "responseEncPayload")
            parameter("updateMask.fieldPaths", "status")
            setBody(body)
        }
        if (!patch.status.isSuccess()) {
            error("Firestore relay complete failed (${patch.status}): ${patch.bodyAsText()}")
        }
    }

    actual suspend fun deleteDiagnosticsRelaySession(uid: String, sessionId: String) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val url =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/diagnosticsRelay/$sessionId"
        client.delete(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    actual suspend fun failDiagnosticsRelaySession(uid: String, sessionId: String) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val url =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/diagnosticsRelay/$sessionId"
        val body = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put(
                        "status",
                        buildJsonObject { put("stringValue", DiagnosticsRelayStatus.FAILED) }
                    )
                }
            )
        }
        val patch = client.patch(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("currentDocument.exists", "true")
            parameter("updateMask.fieldPaths", "status")
            setBody(body)
        }
        if (!patch.status.isSuccess()) {
            error("Firestore relay fail mark failed (${patch.status}): ${patch.bodyAsText()}")
        }
    }

    actual suspend fun fetchCloudDeviceRecord(uid: String, deviceId: String): CloudDeviceRecord? {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val url =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices/$deviceId"
        val response = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            error("Firestore device fetch failed (${response.status}): ${response.bodyAsText()}")
        }
        val doc = desktopJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return parseCloudDeviceDocument(doc)
    }

    actual fun observeDiagnosticsRelayInbox(
        uid: String,
        responderDeviceId: String,
        onSession: (DiagnosticsRelaySession) -> Unit,
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
                                "users/$uid/diagnosticsRelay"
                        val response = client.get(url) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        if (!response.status.isSuccess()) {
                            error("Firestore relay list failed (${response.status}): ${response.bodyAsText()}")
                        }
                        val body = desktopJson.parseToJsonElement(response.bodyAsText()).jsonObject
                        val docsEl = body["documents"]
                        val arr = docsEl as? JsonArray
                        arr?.forEach { el ->
                            val session = parseRelaySessionDocument(el.jsonObject) ?: return@forEach
                            if (session.responderDeviceId != responderDeviceId) return@forEach
                            if (session.status != DiagnosticsRelayStatus.PENDING) return@forEach
                            if (!state.stopped) onSession(session)
                        }
                    }.onFailure { error ->
                        if (!state.stopped) onError(error)
                    }
                    if (state.stopped) break
                    delay(RELAY_POLL_MS)
                }
            } finally {
                if (!idle.isCompleted) idle.complete(Unit)
            }
        }
        return object : CloudRegistryHandle {
            override fun stop() {
                if (state.stopped) return
                state.stopped = true
                job.cancel()
            }

            override suspend fun awaitIdle() {
                idle.await()
            }
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
                            val record = parseCloudDeviceDocument(el.jsonObject) ?: return@forEach
                            list += record
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
        documentId: String,
        body: JsonObject,
        fieldPaths: List<String>
    ) {
        val patchUrl = "$parent/$documentId"
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
            parameter("documentId", documentId)
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
        clientVersion: String,
        clientVersionCode: Int,
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
                put("clientVersion", buildJsonObject { put("stringValue", clientVersion) })
                put(
                    "clientVersionCode",
                    buildJsonObject { put("integerValue", clientVersionCode.toString()) }
                )
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

    private fun booleanField(fields: JsonObject, name: String): Boolean? =
        fields[name]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    private fun relaySessionFieldPaths(): List<String> = listOf(
        "sessionId",
        "requesterDeviceId",
        "responderDeviceId",
        "requestEncPayload",
        "responseEncPayload",
        "status",
        "createdAtEpochMs",
        "ttlEpochMs"
    )

    private fun relaySessionDocumentBody(session: DiagnosticsRelaySession): JsonObject = buildJsonObject {
        put(
            "fields",
            buildJsonObject {
                put("sessionId", buildJsonObject { put("stringValue", session.sessionId) })
                put("requesterDeviceId", buildJsonObject { put("stringValue", session.requesterDeviceId) })
                put("responderDeviceId", buildJsonObject { put("stringValue", session.responderDeviceId) })
                put("requestEncPayload", buildJsonObject { put("stringValue", session.requestEncPayload) })
                put("responseEncPayload", buildJsonObject { put("stringValue", session.responseEncPayload) })
                put("status", buildJsonObject { put("stringValue", session.status) })
                put(
                    "createdAtEpochMs",
                    buildJsonObject { put("integerValue", session.createdAtEpochMs.toString()) }
                )
                put(
                    "ttlEpochMs",
                    buildJsonObject { put("integerValue", session.ttlEpochMs.toString()) }
                )
            }
        )
    }

    private fun parseCloudDeviceDocument(doc: JsonObject): CloudDeviceRecord? {
        val fields = doc["fields"]?.jsonObject ?: return null
        val documentId = doc["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/').orEmpty()
        val data = buildMap<String, Any?> {
            put("deviceId", stringField(fields, "deviceId"))
            put("deviceName", stringField(fields, "deviceName"))
            put("lastKnownIp", stringField(fields, "lastKnownIp"))
            put("port", integerField(fields, "port")?.toInt())
            put("publicKeyHash", stringField(fields, "publicKeyHash"))
            put("rootPath", stringField(fields, "rootPath"))
            put("platform", stringField(fields, "platform"))
            put("clientVersion", stringField(fields, "clientVersion"))
            put("clientVersionCode", integerField(fields, "clientVersionCode")?.toInt())
            put("updatedAtEpochMs", integerField(fields, "updatedAtEpochMs"))
            put("fcmToken", stringField(fields, "fcmToken"))
            put("diagnosticsPublicKey", stringField(fields, "diagnosticsPublicKey"))
            put("deviceDetailsCloudEnabled", booleanField(fields, "deviceDetailsCloudEnabled"))
        }
        return CloudDeviceRecordParsing.fromFirestoreMap(data, documentId)
    }

    private fun parseRelaySessionDocument(doc: JsonObject): DiagnosticsRelaySession? {
        val fields = doc["fields"]?.jsonObject ?: return null
        val data = buildMap<String, Any?> {
            put("sessionId", stringField(fields, "sessionId"))
            put("requesterDeviceId", stringField(fields, "requesterDeviceId"))
            put("responderDeviceId", stringField(fields, "responderDeviceId"))
            put("requestEncPayload", stringField(fields, "requestEncPayload"))
            put("responseEncPayload", stringField(fields, "responseEncPayload"))
            put("status", stringField(fields, "status"))
            put("createdAtEpochMs", integerField(fields, "createdAtEpochMs"))
            put("ttlEpochMs", integerField(fields, "ttlEpochMs"))
        }
        return DiagnosticsRelaySession.fromFirestore(data)
    }

    private const val KEY_ID_TOKEN = "id_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_UID = "uid"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val POLL_MS = 12_000L
    private const val RELAY_POLL_MS = 2_000L
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
