package com.fileapex.data.settings

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Wraps and unwraps a secret with something the platform protects (Keystore, Keychain, DPAPI, keyring). */
interface SecretSealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Settings store that never keeps the listed keys in clear text. A value is stored as `sealed:1:<base64>`. A plain
 * value left by an older version is sealed the first time it is read. If a secret cannot be sealed it is kept as it was
 * and sealed on a later read; if it cannot be opened it reads as missing and the stored value is left untouched.
 * Values are never logged. Each secret is opened once per run and then served from memory.
 */
class SealingKvStore(
    private val inner: SettingsKvStore,
    private val sealer: SecretSealer,
    private val sealedKeys: Set<String>,
    private val log: (String) -> Unit = { println(it) }
) : SettingsKvStore {
    private val opened = ConcurrentHashMap<String, String>()

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
        inner.remove(key)
    }

    override fun clear() {
        opened.clear()
        inner.clear()
    }

    override fun getString(key: String, default: String): String {
        if (key !in sealedKeys) return inner.getString(key, default)
        opened[key]?.let { return it }
        val stored = inner.getString(key, "")
        if (stored.isEmpty()) return default
        if (stored.startsWith(PREFIX)) {
            val plain = runCatching {
                String(sealer.open(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)
            }.getOrElse {
                log("SealingKvStore: a stored secret could not be opened (${it.javaClass.simpleName}); treating it as missing")
                return default
            }
            opened[key] = plain
            return plain
        }
        // Written in clear text by an older version: seal it now.
        sealInto(key, stored)
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
        sealInto(key, value)
    }

    private fun sealInto(key: String, plain: String) {
        val sealed = runCatching { sealer.seal(plain.toByteArray(Charsets.UTF_8)) }.getOrElse {
            // Losing a PIN or a key would be worse than keeping it as it was; the next read tries again.
            log("SealingKvStore: a secret could not be sealed (${it.javaClass.simpleName}); it stays as stored for now")
            inner.putString(key, plain)
            return
        }
        inner.putString(key, PREFIX + Base64.getEncoder().encodeToString(sealed))
    }

    private companion object {
        const val PREFIX = "sealed:1:"
    }
}
