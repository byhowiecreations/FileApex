package com.fileapex.tailscale

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val TAILSCALE_ADMIN_KEYS_URL = "https://login.tailscale.com/admin/settings/keys"

enum class TailscalePhase {
    Off,
    NeedsKey,
    Starting,
    Up,
    Down,
    Failed,
    NeedsLogin,
    KeyExpired
}

data class TailscaleLinkSnapshot(
    val phase: TailscalePhase,
    val tailnetIp: String = "",
    val detail: String = "",
    val authUrl: String = ""
)

data class TailscaleUiState(
    val enabled: Boolean = false,
    val authKey: String = "",
    val configurationSaved: Boolean = false,
    val phase: TailscalePhase = TailscalePhase.Off,
    val tailnetIp: String = "",
    val detail: String = "",
    val saveDetail: String = "",
    val authUrl: String = ""
)

fun isTailscaleAuthKey(authKey: String): Boolean {
    val key = authKey.trim()
    return key.startsWith("tskey-auth-") && key.length >= 20
}

private val tailscaleStateJson = Json { ignoreUnknownKeys = true }

/**
 * True when a saved pre-auth key or a logged-in node is already on disk.
 * Neither check contacts Tailscale.
 */
fun tailscaleReadyWithoutLogin(authKey: String, stateJson: String?): Boolean {
    if (isTailscaleAuthKey(authKey)) return true
    if (stateJson.isNullOrBlank()) return false
    return tailscaleStateIsSignedIn(stateJson)
}

/** Local node store only. The private key is never returned. */
fun tailscaleStateIsSignedIn(stateJson: String): Boolean {
    val root = parseStateObject(stateJson) ?: return false
    for ((_, value) in root) {
        val prefs = stateEntryPrefs(value) ?: continue
        if (prefs["LoggedOut"].boolOrNull() == true) continue
        val key = (prefs["Config"] as? JsonObject)?.get("PrivateNodeKey").textOrNull() ?: continue
        if (isStoredNodePrivateKey(key)) return true
    }
    return false
}

internal fun isStoredNodePrivateKey(value: String): Boolean {
    if (!value.startsWith("privkey:")) return false
    val hex = value.removePrefix("privkey:")
    if (hex.length != 64) return false
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
    return hex.any { it != '0' }
}

internal fun readTailscaleNodeState(file: File): String? {
    if (!file.isFile) return null
    val length = file.length()
    if (length <= 0L || length > 4L * 1024 * 1024) return null
    return try {
        file.readText(Charsets.UTF_8)
    } catch (error: IOException) {
        null
    }
}

private fun parseStateObject(text: String): JsonObject? {
    return try {
        tailscaleStateJson.parseToJsonElement(text) as? JsonObject
    } catch (error: IllegalArgumentException) {
        null
    }
}

private fun stateEntryPrefs(value: JsonElement): JsonObject? {
    if (value is JsonObject) return value
    val text = value.textOrNull() ?: return null
    parseStateObject(text)?.let { return it }
    val decoded = decodeStateBytes(text) ?: return null
    return parseStateObject(decoded)
}

private fun decodeStateBytes(text: String): String? {
    return try {
        String(Base64.getDecoder().decode(text), Charsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        null
    }
}

private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** 100.64.0.0/10. Ordinary LAN addresses stay on the LAN path. */
fun isTailscaleIPv4(host: String): Boolean {
    val parts = host.trim().split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    if (parts[2].toIntOrNull() == null || parts[3].toIntOrNull() == null) return false
    return first == 100 && second in 64..127
}

/** Server.Dial is used only while the node is Up and the host is a tailnet address. */
fun usesUserspaceDial(host: String, phase: TailscalePhase): Boolean =
    phase == TailscalePhase.Up && isTailscaleIPv4(host)

/** Stable DNS label from the device id. Used only when the display name is blank. */
fun tailscaleNodeHostname(deviceId: String): String {
    val body = deviceId.lowercase()
        .filter { ch -> ch in 'a'..'z' || ch in '0'..'9' }
        .ifBlank { "device" }
        .take(50)
    return "fileapex-$body".take(63)
}

/** MagicDNS label written into the admin console. A blank name keeps the device id label. */
fun tailscaleConsoleHostname(deviceName: String, deviceId: String): String {
    if (deviceName.isBlank()) return tailscaleNodeHostname(deviceId)
    val named = tailscaleHostname(deviceName)
    if (named == "fileapex-device") return tailscaleNodeHostname(deviceId)
    return named
}

/** DNS label Tailscale will accept for this device. */
fun tailscaleHostname(deviceName: String): String {
    val cleaned = deviceName.lowercase()
        .map { ch -> if (ch in 'a'..'z' || ch in '0'..'9') ch else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-{2,}"), "-")
        .take(50)
        .trim('-')
    return "fileapex-${cleaned.ifBlank { "device" }}".take(63)
}

/**
 * Userspace tsnet only. A blank key never starts the node. An assigned tailnet IP
 * is shown only when the userspace stack reports one.
 */
fun reconcileTailscale(
    enabled: Boolean,
    authKey: String,
    engine: TailscaleLinkSnapshot?,
    saveDetail: String = ""
): TailscaleUiState {
    val key = authKey.trim()
    val saved = isTailscaleAuthKey(key)
    if (!enabled) {
        return TailscaleUiState(
            enabled = false,
            authKey = key,
            configurationSaved = saved,
            phase = TailscalePhase.Off,
            saveDetail = if (saved) "" else saveDetail
        )
    }
    val base = TailscaleUiState(
        enabled = true,
        authKey = key,
        configurationSaved = saved,
        saveDetail = if (saved) "" else saveDetail,
        authUrl = tailscaleLoginUrl(engine?.authUrl.orEmpty()).orEmpty()
    )
    if (engine == null) {
        return base.copy(phase = TailscalePhase.Starting)
    }
    return base.copy(
        phase = engine.phase,
        tailnetIp = engine.tailnetIp.trim(),
        detail = engine.detail
    )
}

/** Sent to MainActivity when the login tab closes and the node is registered. */
const val TAILSCALE_LOGIN_RETURN_URL = "fileapex://auth-callback"

/** Interactive login pages only. Anything else is ignored. */
fun tailscaleLoginUrl(raw: String): String? {
    val url = raw.trim()
    if (!url.startsWith("https://")) return null
    val host = url.removePrefix("https://").substringBefore('/').lowercase()
    if (host != "login.tailscale.com" && !host.endsWith(".tailscale.com")) return null
    return url
}

/** A saved key does not start the node. Start failures leave the switch off. */
fun tailscaleAfterBringUp(authKey: String, snapshot: TailscaleLinkSnapshot): TailscaleUiState {
    val key = authKey.trim()
    val saved = isTailscaleAuthKey(key)
    if (snapshot.detail == "tsnet_not_linked" ||
        snapshot.phase == TailscalePhase.Down ||
        snapshot.phase == TailscalePhase.Failed
    ) {
        val failed = snapshot.phase == TailscalePhase.Failed && snapshot.detail != "tsnet_not_linked"
        return TailscaleUiState(
            enabled = false,
            authKey = key,
            configurationSaved = saved,
            phase = if (failed) TailscalePhase.Failed else TailscalePhase.Down,
            detail = snapshot.detail.ifBlank {
                if (failed) "start_failed" else "tsnet_not_linked"
            },
            tailnetIp = ""
        )
    }
    return reconcileTailscale(enabled = true, authKey = key, engine = snapshot)
}

/**
 * v1.104 BackendState is NoState, InUseOtherUser, NeedsLogin, NeedsMachineAuth,
 * Stopped, Starting, or Running. Key expiry is Status.Self.Expired or a past KeyExpiry.
 */
fun snapshotFromBackend(backendState: String, keyExpired: Boolean, tailnetIp: String): TailscaleLinkSnapshot {
    val ip = tailnetIp.trim()
    if (keyExpired) {
        return TailscaleLinkSnapshot(TailscalePhase.KeyExpired, tailnetIp = ip, detail = "key_expired")
    }
    return when (backendState) {
        "Running" -> if (ip.isEmpty()) {
            TailscaleLinkSnapshot(TailscalePhase.Starting)
        } else {
            TailscaleLinkSnapshot(TailscalePhase.Up, tailnetIp = ip)
        }
        "NeedsLogin", "NeedsMachineAuth" -> TailscaleLinkSnapshot(
            TailscalePhase.NeedsLogin,
            detail = "needs_login"
        )
        "Starting", "NoState" -> TailscaleLinkSnapshot(TailscalePhase.Starting)
        "Stopped" -> TailscaleLinkSnapshot(TailscalePhase.Down, detail = "stopped")
        else -> TailscaleLinkSnapshot(
            TailscalePhase.Failed,
            detail = backendState.trim().take(180).ifBlank { "start_failed" }
        )
    }
}

fun tailscaleAuthKeyFingerprint(authKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(authKey.trim().encodeToByteArray())
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

/** True when the saved enrollment hash does not match the key, or the user asked to re-enroll. */
fun tailscaleEnrollmentReset(savedFingerprint: String, authKey: String, force: Boolean): Boolean {
    if (force) return true
    if (!isTailscaleAuthKey(authKey)) return false
    val saved = savedFingerprint.trim()
    if (saved.isEmpty()) return false
    return saved != tailscaleAuthKeyFingerprint(authKey)
}

fun evaluateTsnetBringUp(
    authKey: String,
    nativeLinked: Boolean,
    tailnetIp: String
): TailscaleLinkSnapshot {
    if (!isTailscaleAuthKey(authKey)) {
        return TailscaleLinkSnapshot(TailscalePhase.Failed, detail = "invalid_auth_key")
    }
    if (!nativeLinked) {
        return TailscaleLinkSnapshot(TailscalePhase.Down, detail = "tsnet_not_linked")
    }
    val ip = tailnetIp.trim()
    if (ip.isEmpty()) {
        return TailscaleLinkSnapshot(TailscalePhase.Starting)
    }
    return TailscaleLinkSnapshot(TailscalePhase.Up, tailnetIp = ip)
}

/** Android denies NETLINK_ROUTE to app processes. That refusal is not a bad key. */
internal fun isTailscaleSandboxDenial(detail: String): Boolean {
    val text = detail.lowercase()
    return "netlinkrib" in text || "netlink_route" in text
}

/** A sandbox refusal leaves the node unlinked. Any other start error stays visible. */
internal fun tailscaleStartFailure(detail: String): TailscaleLinkSnapshot {
    val trimmed = detail.trim().take(180)
    if (isTailscaleSandboxDenial(trimmed)) {
        return TailscaleLinkSnapshot(phase = TailscalePhase.Down, detail = "tsnet_not_linked")
    }
    return TailscaleLinkSnapshot(
        phase = TailscalePhase.Failed,
        detail = trimmed.ifBlank { "start_failed" }
    )
}
