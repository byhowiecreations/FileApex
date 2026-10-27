package com.fileapex.cloud.seal

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.SecretAvailability
import com.fileapex.data.settings.SecretUnavailableException
import com.fileapex.di.FileApexServices

/** Holds this device's copy of the [ClusterRecordKey]; the settings store keeps it sealed by the platform vault. */
object ClusterRecordKeys {
    private val lock = Any()
    private var loaded = false
    private var held: ClusterRecordKey? = null

    /** Called after the key changed (adopted from a peer or rotated), so the sealed record fields can be written again. */
    @Volatile
    var onChanged: () -> Unit = {}

    internal var read: () -> String = { FileApexServices.settings.clusterRecordKeyJson() }
    internal var write: (String) -> Unit = { FileApexServices.settings.setClusterRecordKeyJson(it) }

    fun current(): ClusterRecordKey? = synchronized(lock) {
        if (!loaded) {
            held = ClusterRecordKey.fromJson(read())
            loaded = held != null
        }
        held
    }

    /** The key, made on first use when no peer has handed one over yet; null while the platform vault cannot be opened. */
    fun ensure(): ClusterRecordKey? = synchronized(lock) {
        current()?.let { return it }
        try {
            SecretAvailability.requireAvailable(BaseAppSettings.KEY_CLUSTER_RECORD_KEY)
        } catch (_: SecretUnavailableException) {
            return null
        }
        ClusterRecordKey.generate().also(::store)
    }

    /** Takes a key a paired peer sent when it beats the one held. True when it replaced it. */
    fun adopt(candidate: ClusterRecordKey): Boolean {
        val replaced = synchronized(lock) {
            if (!candidate.isWellFormed() || !candidate.beats(current())) return false
            store(candidate)
            true
        }
        if (replaced) onChanged()
        return replaced
    }

    /** Drops the held key, for a device that was removed from the cluster. */
    fun forget() = synchronized(lock) { write(""); held = null; loaded = false }

    /** A new epoch after a device left the cluster: the removed device's copy of the old key stops matching. */
    fun rotate() {
        synchronized(lock) {
            val old = current() ?: return
            store(ClusterRecordKey.generate(old.epoch + 1))
        }
        onChanged()
    }

    private fun store(key: ClusterRecordKey) {
        write(key.toJson())
        held = key
        loaded = true
    }

    internal fun forgetForTests() = synchronized(lock) { held = null; loaded = false }
}
