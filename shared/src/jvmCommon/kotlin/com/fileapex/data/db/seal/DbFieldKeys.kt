package com.fileapex.data.db.seal

import com.fileapex.security.wipe
import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.SecretAvailability
import com.fileapex.data.settings.SecretUnavailableException
import com.fileapex.di.FileApexServices
import java.security.SecureRandom
import java.util.Base64

/**
 * The database field key. A key that exists but cannot be opened right now is never replaced (that would make everything
 * sealed with it unreadable), and a new key the platform vault could not store yet is not used (it would be lost on
 * restart). Without a key the sealed columns are locked, not written in clear text; the lookup is tried again every few
 * seconds, so a keychain that unlocks later is picked up in the same run.
 */
object DbFieldKeys {
    private const val RETRY_MS = 5_000L
    private val lock = Any()
    @Volatile private var current: FieldSealer? = null
    @Volatile private var lastTryMs = 0L

    fun sealer(): FieldSealer? {
        current?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastTryMs < RETRY_MS) return null
        synchronized(lock) {
            current?.let { return it }
            if (System.currentTimeMillis() - lastTryMs < RETRY_MS) return null
            lastTryMs = System.currentTimeMillis()
            current = load()
        }
        return current
    }

    private fun load(): FieldSealer? {
        val settings = FileApexServices.settings
        val stored = runCatching { Base64.getDecoder().decode(settings.dbFieldKeyBase64()) }.getOrNull()
        if (stored != null && stored.size == FieldSealer.KEY_BYTES) return FieldSealer(stored).also { stored.wipe() }
        try {
            SecretAvailability.requireAvailable(BaseAppSettings.KEY_DB_FIELD_KEY)
        } catch (_: SecretUnavailableException) {
            println("DbFieldKeys: the stored key cannot be opened right now; the database stays locked")
            return null
        }
        val fresh = ByteArray(FieldSealer.KEY_BYTES).also(SecureRandom()::nextBytes)
        settings.setDbFieldKeyBase64(Base64.getEncoder().encodeToString(fresh))
        if (!settings.dbFieldKeyIsStored()) {
            println("DbFieldKeys: the new key could not be stored yet; the database stays locked")
            return null
        }
        return FieldSealer(fresh).also { fresh.wipe() }
    }
}
