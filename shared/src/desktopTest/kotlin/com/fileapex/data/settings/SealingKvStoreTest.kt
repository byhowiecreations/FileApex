package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SealingKvStoreTest {
    private class MapStore : SettingsKvStore {
        val strings = linkedMapOf<String, String>()
        val ints = linkedMapOf<String, Int>()
        override fun contains(key: String) = key in strings || key in ints
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, default: String) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getLong(key: String, default: Long) = default
        override fun putLong(key: String, value: Long) {}
        override fun clear() { strings.clear(); ints.clear() }
        override fun keys(prefix: String) = (strings.keys + ints.keys).filter { it.startsWith(prefix) }
        override fun remove(key: String) { strings.remove(key); ints.remove(key) }
    }

    /** Reversible and recognisably not the plain text, so a test can see what reached the store. */
    private class FakeSealer : SecretSealer {
        var failSeal = false
        var failOpen = false
        override fun seal(plain: ByteArray): ByteArray {
            check(!failSeal) { "seal failed" }
            return plain.reversedArray()
        }
        override fun open(sealed: ByteArray): ByteArray {
            check(!failOpen) { "open failed" }
            return sealed.reversedArray()
        }
    }

    private val secret = "super-secret-value-123"
    private val sealedKeys = setOf("device_pin", "clipboard_private_key_b64")

    private fun store(inner: MapStore = MapStore(), sealer: FakeSealer = FakeSealer()) =
        Triple(SealingKvStore(inner, sealer, sealedKeys, log = {}), inner, sealer)

    @Test
    fun aSecretIsNeverWrittenAsPlainText() {
        val (store, inner, _) = store()
        store.putString("device_pin", secret)
        val written = inner.strings.getValue("device_pin")
        assertTrue(written.startsWith("sealed:1:"))
        assertFalse(written.contains(secret))
        assertEquals(secret, store.getString("device_pin", ""))
    }

    @Test
    fun otherSettingsStayReadable() {
        val (store, inner, _) = store()
        store.putString("app_theme", "dark")
        assertEquals("dark", inner.strings["app_theme"])
        assertEquals("dark", store.getString("app_theme", ""))
    }

    @Test
    fun aPlainValueFromAnOlderVersionIsSealedWhenFirstRead() {
        val (store, inner, _) = store()
        inner.strings["clipboard_private_key_b64"] = secret
        assertEquals(secret, store.getString("clipboard_private_key_b64", ""))
        val now = inner.strings.getValue("clipboard_private_key_b64")
        assertTrue(now.startsWith("sealed:1:"))
        assertFalse(now.contains(secret))
    }

    @Test
    fun aSecretThatCannotBeOpenedReadsAsMissingAndIsLeftAlone() {
        val sealer = FakeSealer()
        val (store, inner, _) = store(sealer = sealer)
        store.putString("device_pin", secret)
        val sealedValue = inner.strings.getValue("device_pin")
        sealer.failOpen = true
        val reopened = SealingKvStore(inner, sealer, sealedKeys, log = {})
        assertEquals("fallback", reopened.getString("device_pin", "fallback"))
        assertEquals(sealedValue, inner.strings["device_pin"])
    }

    @Test
    fun ifSealingFailsTheValueIsKeptAndSealedOnTheNextRead() {
        val sealer = FakeSealer()
        val (store, inner, _) = store(sealer = sealer)
        sealer.failSeal = true
        store.putString("device_pin", secret)
        assertEquals(secret, inner.strings["device_pin"])
        sealer.failSeal = false
        assertEquals(secret, SealingKvStore(inner, sealer, sealedKeys, log = {}).getString("device_pin", ""))
        assertTrue(inner.strings.getValue("device_pin").startsWith("sealed:1:"))
    }

    @Test
    fun anEmptySecretClearsTheStoredValue() {
        val (store, inner, _) = store()
        store.putString("device_pin", secret)
        store.putString("device_pin", "")
        assertEquals("", inner.strings["device_pin"])
        assertEquals("none", store.getString("device_pin", "none").ifEmpty { "none" })
    }

    @Test
    fun removeAndClearForgetSecrets() {
        val (store, inner, _) = store()
        store.putString("device_pin", secret)
        store.remove("device_pin")
        assertFalse(inner.contains("device_pin"))
        store.putString("device_pin", secret)
        store.clear()
        assertEquals("x", store.getString("device_pin", "x"))
    }

    @Test
    fun theSecretIsOpenedOncePerRunNotOnEveryRead() {
        var opens = 0
        val counting = object : SecretSealer {
            override fun seal(plain: ByteArray) = plain.reversedArray()
            override fun open(sealed: ByteArray): ByteArray { opens++; return sealed.reversedArray() }
        }
        val inner = MapStore()
        SealingKvStore(inner, counting, sealedKeys, log = {}).putString("device_pin", secret)
        val reopened = SealingKvStore(inner, counting, sealedKeys, log = {})
        repeat(5) { reopened.getString("device_pin", "") }
        assertEquals(1, opens)
    }
}
