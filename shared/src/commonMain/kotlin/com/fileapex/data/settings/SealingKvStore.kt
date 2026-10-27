package com.fileapex.data.settings

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Wraps and unwraps a secret with something the platform protects (Keystore, Keychain, DPAPI, keyring). */
interface SecretSealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray

    /** True for a blob sealed in a way this sealer no longer uses for new secrets; it is sealed again once opened. */
    fun needsReseal(sealed: ByteArray): Boolean = false
}

/**
 * The key that wraps a secret may exist but cannot be read right now (locked keychain, keyring not ready, keystore not
 * started). It is not the same as a key that is gone: the secret must not be replaced while this lasts.
 */
class SecretUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Settings keys whose stored secret exists but could not be opened because its wrapping key was unavailable. */
object SecretAvailability {
    private val unavailable = ConcurrentHashMap.newKeySet<String>()

    fun isUnavailable(key: String): Boolean = key in unavailable

    /** Call before generating a replacement for [key]: a secret that exists but cannot be opened must not be replaced. */
    fun requireAvailable(key: String) {
        if (isUnavailable(key)) throw SecretUnavailableException("the stored secret cannot be opened right now")
    }
    internal fun markUnavailable(key: String) { unavailable.add(key) }
    internal fun markAvailable(key: String) { unavailable.remove(key) }
}

/**
 * Settings store that never keeps the listed keys in clear text. A value is stored as `sealed:1:<base64>`. A plain
 * value left by an older version is sealed the first time it is read (if that fails it stays as it was and is tried
 * again). A new value that cannot be sealed is kept in memory only and sealed on a later read; it is never written in
 * clear text. If a secret cannot be opened it reads as missing and the stored value is left untouched.
 * Values are never logged. Each secret is opened once per run and then served from memory.
 */
class SealingKvStore(
    private val inner: SettingsKvStore,
    private val sealer: SecretSealer,
    private val sealedKeys: Set<String>,
    private val log: (String) -> Unit = { println(it) }
) : SettingsKvStore {
    private val opened = ConcurrentHashMap<String, String>()
    private val pendingSeal = ConcurrentHashMap.newKeySet<String>()

    /** True while [key] was set but could not be sealed yet, so it exists in memory only. */
    fun isPendingSeal(key: String): Boolean = key in pendingSeal

    override fun contains(key: String): Boolean = inner.contains(key)
    override fun getBoolean(key: String, default: Boolean): Boolean = inner.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = inner.putBoolean(key, value)
    override fun getInt(key: String, default: Int): Int = inner.getInt(key, default)
    override fun putInt(key: String, value: Int) = inner.putInt(key, value)
    override fun getLong(key: String, default: Long): Long = inner.getLong(key, default)
    override fun putLong(key: String, value: Long) = inner.putLong(key, value)
    override fun keys(prefix: String): List<String> = inner.keys(prefix)

    override fun remove(key: String) {
        opened.remove(key)
        SecretAvailability.markAvailable(key)
        inner.remove(key)
    }

    override fun clear() {
        opened.keys.forEach { SecretAvailability.markAvailable(it) }
        sealedKeys.forEach { SecretAvailability.markAvailable(it) }
        opened.clear()
        inner.clear()
    }

    override fun getString(key: String, default: String): String {
        if (key !in sealedKeys) return inner.getString(key, default)
        opened[key]?.let {
            if (key in pendingSeal) sealInto(key, it, legacyPlain = false)
            return it
        }
        val stored = inner.getString(key, "")
        if (stored.isEmpty()) return default
        if (stored.startsWith(PREFIX)) {
            val blob = runCatching { Base64.getDecoder().decode(stored.removePrefix(PREFIX)) }.getOrNull()
            val plain = runCatching { String(sealer.open(checkNotNull(blob)), Charsets.UTF_8) }.getOrElse {
                if (generateSequence(it) { e -> e.cause }.any { e -> e is SecretUnavailableException }) {
                    SecretAvailability.markUnavailable(key)
                    log("SealingKvStore: a stored secret cannot be opened right now (${it.javaClass.simpleName}); it is kept and tried again")
                } else {
                    log("SealingKvStore: a stored secret could not be opened (${it.javaClass.simpleName}); treating it as missing")
                }
                return default
            }
            SecretAvailability.markAvailable(key)
            opened[key] = plain
            if (blob != null && sealer.needsReseal(blob)) sealInto(key, plain, legacyPlain = false)
            return plain
        }
        // Written in clear text by an older version: seal it now.
        sealInto(key, stored, legacyPlain = true)
        opened[key] = stored
        return stored
    }

    override fun putString(key: String, value: String) {
        if (key !in sealedKeys) {
            inner.putString(key, value)
            return
        }
        if (value.isEmpty()) {
            opened.remove(key)
            inner.putString(key, "")
            return
        }
        opened[key] = value
        SecretAvailability.markAvailable(key)
        sealInto(key, value, legacyPlain = false)
    }

    private fun sealInto(key: String, plain: String, legacyPlain: Boolean) {
        val sealed = runCatching { sealer.seal(plain.toByteArray(Charsets.UTF_8)) }.getOrElse {
            if (legacyPlain) {
                // Already in clear text from an older version: leave it until it can be sealed.
                log("SealingKvStore: a secret could not be sealed (${it.javaClass.simpleName}); it stays as stored for now")
            } else {
                pendingSeal.add(key)
                log("SealingKvStore: a secret could not be sealed (${it.javaClass.simpleName}); it is kept in memory and tried again")
            }
            return
        }
        pendingSeal.remove(key)
        inner.putString(key, PREFIX + Base64.getEncoder().encodeToString(sealed))
    }

    private companion object {
        const val PREFIX = "sealed:1:"
    }
}
