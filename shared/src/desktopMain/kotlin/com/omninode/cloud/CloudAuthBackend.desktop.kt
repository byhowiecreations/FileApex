package com.omninode.cloud

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.ktor.serialization.kotlinx.json.json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(desktopJson) }
    }
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

    actual suspend fun publishDevice(uid: String, record: CloudDeviceRecord) {
        val token = requireIdToken()
        val project = firebaseProjectId()
        val parent =
            "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/" +
                "users/$uid/devices"
        val fields = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put("deviceId", buildJsonObject { put("stringValue", record.deviceId) })
                    put("deviceName", buildJsonObject { put("stringValue", record.deviceName) })
                    put("lastKnownIp", buildJsonObject { put("stringValue", record.lastKnownIp) })
                    put("port", buildJsonObject { put("integerValue", record.port.toString()) })
                    put("publicKeyHash", buildJsonObject { put("stringValue", record.publicKeyHash) })
                    put("rootPath", buildJsonObject { put("stringValue", record.rootPath) })
                    put("platform", buildJsonObject { put("stringValue", record.platform) })
                    put(
                        "updatedAtEpochMs",
                        buildJsonObject { put("integerValue", record.updatedAtEpochMs.toString()) }
                    )
                }
            )
        }
        // Upsert: PATCH existing document; if missing, create with documentId.
        val patchUrl = "$parent/${record.deviceId}"
        val patch = client.patch(patchUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("currentDocument.exists", "true")
            setBody(fields)
        }
        if (patch.status.isSuccess()) return
        val create = client.post(parent) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("documentId", record.deviceId)
            setBody(fields)
        }
        if (!create.status.isSuccess()) {
            error("Firestore publish failed (${create.status}): ${create.bodyAsText()}")
        }
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
        excludeDeviceId: String,
        onDevices: (List<CloudDeviceRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): CloudRegistryHandle {
        var job: Job? = null
        job = pollScope.launch {
            while (isActive) {
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
                        if (id == excludeDeviceId) return@forEach
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
                    onDevices(list)
                }.onFailure { error ->
                    onError(error)
                }
                delay(POLL_MS)
            }
        }
        return object : CloudRegistryHandle {
            override fun stop() {
                job?.cancel()
            }
        }
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

actual fun googleWebClientId(): String =
    DesktopCloudIds.WEB_CLIENT_ID.ifBlank {
        System.getProperty("omninode.google.web.client.id").orEmpty()
    }

actual fun firebaseApiKey(): String =
    DesktopCloudIds.API_KEY.ifBlank {
        System.getProperty("omninode.firebase.api.key") ?: DEFAULT_API_KEY
    }

actual fun firebaseProjectId(): String =
    DesktopCloudIds.PROJECT_ID.ifBlank {
        System.getProperty("omninode.firebase.project.id") ?: DEFAULT_PROJECT_ID
    }

actual fun currentPlatformLabel(): String = "desktop"

private const val DEFAULT_API_KEY = "REDACTED_FIREBASE_API_KEY"
private const val DEFAULT_PROJECT_ID = "omninode-502915"

private val desktopJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

object DesktopOAuthPkce {
    data class PendingAuth(
        val codeVerifier: String,
        val state: String
    )

    @Volatile
    var pending: PendingAuth? = null

    fun beginAuthorizationUrl(webClientId: String): String {
        val verifier = randomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafe(24)
        pending = PendingAuth(codeVerifier = verifier, state = state)
        return buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(webClientId.encodeUrl())
            append("&redirect_uri=").append(OAUTH_REDIRECT_URI.encodeUrl())
            append("&response_type=code")
            append("&scope=").append("openid%20email%20profile")
            append("&code_challenge=").append(challenge.encodeUrl())
            append("&code_challenge_method=S256")
            append("&state=").append(state.encodeUrl())
            append("&access_type=online")
            append("&prompt=select_account")
        }
    }

    suspend fun exchangeCodeForIdToken(code: String, state: String?): String {
        val pendingAuth = pending ?: error("No pending OAuth request")
        if (state != null && state != pendingAuth.state) {
            error("OAuth state mismatch")
        }
        pending = null
        val client = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(desktopJson)
            }
        }
        return try {
            val response = client.post("https://oauth2.googleapis.com/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    "code=${code.encodeUrl()}" +
                        "&client_id=${googleWebClientId().encodeUrl()}" +
                        "&redirect_uri=${OAUTH_REDIRECT_URI.encodeUrl()}" +
                        "&grant_type=authorization_code" +
                        "&code_verifier=${pendingAuth.codeVerifier.encodeUrl()}"
                )
            }
            if (!response.status.isSuccess()) {
                error("Token exchange failed (${response.status}): ${response.bodyAsText()}")
            }
            val obj = desktopJson.parseToJsonElement(response.bodyAsText()).jsonObject
            obj["id_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Token response missing id_token")
        } finally {
            client.close()
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8)
}
