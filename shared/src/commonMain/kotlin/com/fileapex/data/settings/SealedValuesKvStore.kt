package com.fileapex.data.settings

import com.fileapex.data.db.seal.FieldSealer
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The key that seals every setting value. It is 32 random bytes made once and kept in the settings file itself,
 * wrapped by the platform ([SecretSealer]: Keystore, Keychain, DPAPI, keyring). A key that exists but cannot be opened
 * right now (locked keychain) is never replaced: [sealer] returns null and the next call tries again. A key that is
 * gone for good (damaged blob, key lost) is replaced, and the values sealed under the old one read as missing.
 */
class SettingsMasterKey(
    private val inner: SettingsKvStore,
    private val wrapper: SecretSealer,
    private val log: (String) -> Unit = { println(it) }
) {
    @Volatile
    private var cached: FieldSealer? = null

    enum class Outcome { NotYet, Opened, Created, Replaced, Unavailable }

    /** What the last attempt to get the key did, for the lifecycle log. */
    @Volatile
    var outcome: Outcome = Outcome.NotYet
        private set

    /**
     * Under one lock for the whole process: two stores built together on a first launch must not each make a key and
     * overwrite one another, which would leave everything the first one sealed unreadable. The second reads the key the
     * first stored and uses it.
     */
    fun sealer(): FieldSealer? {
        cached?.let { return it }
        return synchronized(PROCESS_LOCK) { cached ?: openOrCreate() }
    }

    private fun openOrCreate(): FieldSealer? {
        val stored = inner.getString(KEY, "")
        var replacing = false
        if (stored.startsWith(PREFIX)) {
            val opened = runCatching { wrapper.open(Base64.getDecoder().decode(stored.removePrefix(PREFIX))) }
            opened.getOrNull()?.takeIf { it.size == KEY_BYTES }?.let {
                outcome = Outcome.Opened
                return FieldSealer(it).also { s -> cached = s }
            }
            val failure = opened.exceptionOrNull()
            if (failure != null && generateSequence(failure) { it.cause }.any { it is SecretUnavailableException }) {
                log("SettingsMasterKey: the settings key cannot be opened right now; settings read as missing until it can")
                outcome = Outcome.Unavailable
                return null
            }
            log("SettingsMasterKey: the settings key is gone; a new one is made and the sealed settings read as missing")
            replacing = true
        }
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = runCatching { wrapper.seal(bytes) }.getOrElse {
            log("SettingsMasterKey: a settings key could not be made now (${it.javaClass.simpleName}); tried again later")
            outcome = Outcome.Unavailable
            return null
        }
        inner.putString(KEY, PREFIX + Base64.getEncoder().encodeToString(wrapped))
        outcome = if (replacing) Outcome.Replaced else Outcome.Created
        return FieldSealer(bytes).also { cached = it }
    }

    companion object {
        private val PROCESS_LOCK = Any()
        const val KEY = "settings_master_v1"
        private const val PREFIX = "sealed:1:"
        private const val KEY_BYTES = 32
    }
}

/**
 * Seals the value of every setting except [excluded] (secrets sealed elsewhere and the master key itself), with the
 * associated data `settings.<key>`, as `fe1:` text carrying a type tag. A value left in clear text by an older version
 * is sealed when its owner first reads it, or by [sealAll]. While the key cannot be opened nothing is written in clear
 * text: a new value stays in memory and is sealed on a later access, and a sealed value reads as missing and is left
 * untouched. Key names stay readable; they hold no user data.
 */
class SealedValuesKvStore(
    private val inner: SettingsKvStore,
    private val sealer: () -> FieldSealer?,
    private val excluded: Set<String>,
    private val log: (String) -> Unit = { println(it) },
    private val secretSealer: (() -> SecretSealer?)? = null
) : SettingsKvStore {
    private val pending = ConcurrentHashMap<String, String>()

    private class Legacy(val tag: Char, val text: String)

    override fun contains(key: String): Boolean = inner.contains(key) || pending.containsKey(key)
    override fun keys(prefix: String): List<String> = (inner.keys(prefix) + pending.keys.filter { it.startsWith(prefix) }).distinct()

    override fun remove(key: String) {
        pending.remove(key)
        inner.remove(key)
    }

    override fun clear() {
        pending.clear()
        inner.clear()
    }

    override fun getString(key: String, default: String): String =
        if (key in excluded) inner.getString(key, default) else read(key)?.second ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (key in excluded) inner.getBoolean(key, default) else read(key)?.let { it.second.toBooleanStrictOrNull() } ?: default

    override fun getInt(key: String, default: Int): Int =
        if (key in excluded) inner.getInt(key, default) else read(key)?.let { it.second.toIntOrNull() } ?: default

    override fun getLong(key: String, default: Long): Long =
        if (key in excluded) inner.getLong(key, default) else read(key)?.let { it.second.toLongOrNull() } ?: default

    override fun putString(key: String, value: String) = if (key in excluded) inner.putString(key, value) else write(key, 's', value)
    override fun putBoolean(key: String, value: Boolean) = if (key in excluded) inner.putBoolean(key, value) else write(key, 'b', value.toString())
    override fun putInt(key: String, value: Int) = if (key in excluded) inner.putInt(key, value) else write(key, 'i', value.toString())
    override fun putLong(key: String, value: Long) = if (key in excluded) inner.putLong(key, value) else write(key, 'l', value.toString())

    class Health(val openable: Int, val unreadable: Int, val clearText: Int, val unreadableKeys: List<String> = emptyList()) {
        override fun toString() = "openable=$openable unreadable=$unreadable clear=$clearText"
    }

    /** How many stored values open with the current key, how many do not, and how many are still clear text. Counts only. */
    fun health(): Health {
        val field = sealer()
        var openable = 0
        var unreadable = 0
        var clearText = 0
        val unreadableKeys = mutableListOf<String>()
        for (key in inner.keys("")) {
            if (key in excluded || pending.containsKey(key)) continue
            val stored = rawString(key) ?: run { clearText++; null } ?: continue
            if (!stored.startsWith(FieldSealer.PREFIX)) {
                clearText++
            } else if (field != null && field.open(TABLE, key, stored) != null) {
                openable++
            } else {
                unreadable++
                unreadableKeys += key
            }
        }
        return Health(openable, unreadable, clearText, unreadableKeys.sorted())
    }

    /** Seals every key that is still in clear text. Returns how many were sealed; keys that cannot be sealed now stay as they are. */
    fun sealAll(): Int {
        val field = sealer() ?: return 0
        var sealed = 0
        for (key in inner.keys("")) {
            if (key in excluded || pending.containsKey(key)) continue
            if (!isSealed(key)) {
                val legacy = readLegacy(key) ?: continue
                var text = legacy.text
                if (text.startsWith(PREFIX_SEALED) || text.startsWith(FieldSealer.PREFIX)) {
                    text = unwrapLegacyNested(key, text, field)
                }
                if (seal(key, legacy.tag, text)) sealed++
            } else {
                val raw = rawString(key)
                if (raw != null) {
                    val opened = field.open(TABLE, key, raw)
                    if (opened != null && opened.length >= 2 && opened[1] == ':') {
                        val tag = opened[0]
                        val text = opened.substring(2)
                        if (text.startsWith(PREFIX_SEALED) || text.startsWith(FieldSealer.PREFIX)) {
                            val clean = unwrapLegacyNested(key, text, field)
                            if (clean != text) {
                                if (seal(key, tag, clean)) sealed++
                            }
                        }
                    }
                }
            }
        }
        return sealed
    }

    private fun isSealed(key: String): Boolean = rawString(key)?.startsWith(FieldSealer.PREFIX) == true

    /** The stored text, or null when the key holds a typed legacy value (a strict preferences store refuses to read it as text). */
    private fun rawString(key: String): String? = try {
        inner.getString(key, "")
    } catch (_: ClassCastException) {
        null
    }

    private fun readLegacy(key: String): Legacy? {
        val text = rawString(key)
        if (text != null) return if (inner.contains(key)) Legacy('s', text) else null
        runCatching { return Legacy('b', inner.getBoolean(key, false).toString()) }
        runCatching { return Legacy('i', inner.getInt(key, 0).toString()) }
        runCatching { return Legacy('l', inner.getLong(key, 0L).toString()) }
        return null
    }

    /** The tag and text of the current value, sealing a legacy one on the way; null when there is none or it cannot be opened now. */
    private fun read(key: String): Pair<Char, String>? {
        pending[key]?.let { payload ->
            if (flush(key, payload)) pending.remove(key)
            return payload[0] to payload.substring(2)
        }
        val stored = rawString(key)
        if (stored != null && stored.startsWith(FieldSealer.PREFIX)) {
            val field = sealer() ?: return null
            val payload = field.open(TABLE, key, stored)
            if (payload == null) {
                log("SealedValuesKvStore: a stored setting could not be opened; it reads as missing")
                return null
            }
            val tag = payload[0]
            var text = payload.substring(2)
            if (text.startsWith(PREFIX_SEALED) || text.startsWith(FieldSealer.PREFIX)) {
                val clean = unwrapLegacyNested(key, text, field)
                if (clean != text) {
                    text = clean
                    seal(key, tag, text)
                }
            }
            return tag to text
        }
        val legacy = readLegacy(key) ?: return null
        val field = sealer()
        var text = legacy.text
        if (field != null && (text.startsWith(PREFIX_SEALED) || text.startsWith(FieldSealer.PREFIX))) {
            text = unwrapLegacyNested(key, text, field)
        }
        seal(key, legacy.tag, text)
        return legacy.tag to text
    }

    private fun unwrapLegacyNested(key: String, initial: String, field: FieldSealer): String {
        var cur = initial
        var rounds = 0
        val wrapper = secretSealer?.invoke()
        while (rounds < 8) {
            rounds++
            if (cur.startsWith(FieldSealer.PREFIX)) {
                val opened = field.open(TABLE, key, cur)
                if (opened != null) {
                    cur = if (opened.length >= 2 && opened[1] == ':') opened.substring(2) else opened
                    continue
                }
            }
            if (wrapper != null && cur.startsWith(PREFIX_SEALED)) {
                val b64 = cur.removePrefix(PREFIX_SEALED)
                val blob = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                val opened = if (blob != null) runCatching { String(wrapper.open(blob), Charsets.UTF_8) }.getOrNull() else null
                if (opened != null) {
                    cur = opened
                    continue
                }
            }
            break
        }
        return cur
    }

    private fun write(key: String, tag: Char, text: String) {
        pending.remove(key)
        if (!seal(key, tag, text)) pending[key] = "$tag:$text"
    }

    private fun flush(key: String, payload: String): Boolean = seal(key, payload[0], payload.substring(2))

    private fun seal(key: String, tag: Char, text: String): Boolean {
        val field = sealer() ?: return false
        inner.putString(key, field.seal(TABLE, key, "$tag:$text"))
        return true
    }

    private companion object {
        const val TABLE = "settings"
        const val PREFIX_SEALED = "sealed:1:"
    }
}
