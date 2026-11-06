package com.fileapex.data.seal

import com.fileapex.data.db.seal.FieldSealer

/** Named string slots of a platform store (Android preferences). */
interface StringSlots {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/**
 * String slots whose values are sealed with [SealedText]. The slot name is bound in with [purpose], so a value cannot be
 * moved to another slot. A plain value left by an older version is returned as is and sealed in place on first read.
 * With no key available nothing is written, what is stored stays untouched and a read gives null.
 */
class SealedStrings(private val slots: StringSlots, private val purpose: String) {
    fun get(key: String): String? {
        val stored = slots.get(key) ?: return null
        val plain = SealedText.open("$purpose:$key", stored) ?: return null
        if (!stored.startsWith(FieldSealer.PREFIX)) put(key, plain)
        return plain
    }

    /** False when no key is available yet; the stored value is then unchanged. */
    fun put(key: String, value: String): Boolean {
        val sealed = SealedText.seal("$purpose:$key", value) ?: return false
        slots.put(key, sealed)
        return true
    }

    fun remove(key: String) = slots.remove(key)
}
