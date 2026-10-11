package com.fileapex.security

import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.util.DeviceIdentityMarkers
import java.security.MessageDigest
import java.util.Base64

/**
 * New installs derive their device id from the SHA-256 of their identity public key, so an id can only
 * be claimed by the holder of that key. Ids minted before 0.16.5c are random UUIDs and stay as they are:
 * [isDerived] is false for them and they are checked by direct contact only.
 */
object IdentityBinding {
    const val DERIVED_PREFIX = "fx-"
    private const val ID_HEX_CHARS = 32
    private const val HASH_HEX_CHARS = 16

    fun isDerived(deviceId: String): Boolean = deviceId.trim().startsWith(DERIVED_PREFIX)

    fun deriveDeviceId(publicKeyBase64: String): String =
        DERIVED_PREFIX + digestHex(publicKeyBase64).take(ID_HEX_CHARS)

    /** Same digest as the id, shortened to the width of the stored `publicKeyHash` field. */
    fun keyHash(publicKeyBase64: String): String = digestHex(publicKeyBase64).take(HASH_HEX_CHARS)

    /** True when [deviceId] is key-derived and [publicKeyBase64] is the key it was derived from. */
    fun matches(deviceId: String, publicKeyBase64: String): Boolean =
        publicKeyBase64.isNotBlank() && deriveDeviceId(publicKeyBase64) == deviceId.trim()

    /**
     * The `publicKeyHash` a node with [deviceId] and [publicKeyBase64] advertises: the key digest for
     * derived ids, the legacy id fingerprint otherwise.
     */
    fun hashFor(deviceId: String, publicKeyBase64: String): String =
        if (isDerived(deviceId) && publicKeyBase64.isNotBlank()) {
            keyHash(publicKeyBase64)
        } else {
            DeviceIdentityMarkers.fingerprint(deviceId)
        }

    fun localHash(deviceId: String): String =
        hashFor(deviceId, runCatching { ClipboardE2ee.publicKeyBase64() }.getOrDefault(""))

    private fun digestHex(publicKeyBase64: String): String {
        val raw = Base64.getDecoder().decode(publicKeyBase64.trim())
        return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }
}

/** Picks this device's id at load time: keeps a pre-0.16.5c id, otherwise derives it from the key. */
object LocalIdentityBinding {
    fun resolve(storedId: String?, fallbackId: () -> String, persist: (String) -> Unit): String {
        val stored = storedId?.trim().orEmpty()
        if (stored.isNotEmpty() && !IdentityBinding.isDerived(stored)) return stored
        val derived = runCatching { IdentityBinding.deriveDeviceId(ClipboardE2ee.publicKeyBase64()) }
            .getOrNull()
        val resolved = derived ?: stored.ifEmpty { fallbackId() }
        if (resolved != stored) persist(resolved)
        return resolved
    }
}
