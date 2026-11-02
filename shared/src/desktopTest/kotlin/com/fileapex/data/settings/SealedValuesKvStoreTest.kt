package com.fileapex.data.settings

import com.fileapex.data.db.seal.FieldSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedValuesKvStoreTest {
    /** Holds typed values like Android preferences: asking for another type than the stored one throws. */
    private class StrictStore : SettingsKvStore {
        val values = linkedMapOf<String, Any>()
        override fun contains(key: String) = key in values
        override fun getBoolean(key: String, default: Boolean) = values[key]?.let { it as Boolean } ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, default: String) = values[key]?.let { it as String } ?: default
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key]?.let { it as Int } ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getLong(key: String, default: Long) = values[key]?.let { it as Long } ?: default
        override fun putLong(key: String, value: Long) { values[key] = value }
        override fun clear() = values.clear()
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { values.remove(key) }
    }

    /** Holds only text like Java preferences: typed values are written as text and parsed back. */
    private class TextStore : SettingsKvStore {
        val values = linkedMapOf<String, String>()
        override fun contains(key: String) = key in values
        override fun getBoolean(key: String, default: Boolean) = values[key]?.toBooleanStrictOrNull() ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key]?.toIntOrNull() ?: default
        override fun putInt(key: String, value: Int) { values[key] = value.toString() }
        override fun getLong(key: String, default: Long) = values[key]?.toLongOrNull() ?: default
        override fun putLong(key: String, value: Long) { values[key] = value.toString() }
        override fun clear() = values.clear()
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { values.remove(key) }
    }

    private val sealer = FieldSealer(ByteArray(32) { (it + 1).toByte() })
    private var unlocked = true
    private val excluded = setOf("pin_hash", "settings_master_v1")

    private fun store(inner: SettingsKvStore) = SealedValuesKvStore(inner, { if (unlocked) sealer else null }, excluded, log = {})

    private fun raw(inner: StrictStore, key: String) = inner.values[key] as String

    @Test
    fun everyKindOfValueRoundTripsAndIsSealedOnDisk() {
        val inner = StrictStore()
        val s = store(inner)
        s.putString("name", "Kitchen iPad"); s.putBoolean("flag", true); s.putInt("count", 7); s.putLong("when", 1_700_000_000_000L)
        assertEquals(listOf("Kitchen iPad", true, 7, 1_700_000_000_000L), listOf(s.getString("name", ""), s.getBoolean("flag", false), s.getInt("count", 0), s.getLong("when", 0L)))
        listOf("name", "flag", "count", "when").forEach { assertTrue("$it sealed", raw(inner, it).startsWith(FieldSealer.PREFIX)) }
        assertFalse(raw(inner, "name").contains("Kitchen"))
    }

    @Test
    fun aDefaultIsReturnedForAnUnsetKeyAndAnUnparsableValue() {
        val s = store(StrictStore())
        assertEquals("d", s.getString("nothing", "d"))
        s.putString("text", "abc")
        assertEquals(true, s.getBoolean("text", true))
        assertEquals(5, s.getInt("text", 5))
    }

    @Test
    fun aLegacyPlainStringIsSealedWhenItIsFirstRead() {
        val inner = StrictStore().also { it.values["name"] = "Honor" }
        val s = store(inner)
        assertEquals("Honor", s.getString("name", ""))
        assertTrue(raw(inner, "name").startsWith(FieldSealer.PREFIX))
        assertEquals("Honor", store(inner).getString("name", ""))
    }

    @Test
    fun legacyTypedValuesOnAStrictStoreAreSealedWhenTheirOwnerReadsThem() {
        val inner = StrictStore().also { it.values["flag"] = true; it.values["count"] = 9; it.values["when"] = 55L }
        val s = store(inner)
        assertEquals(true, s.getBoolean("flag", false))
        assertEquals(9, s.getInt("count", 0))
        assertEquals(55L, s.getLong("when", 0L))
        listOf("flag", "count", "when").forEach { assertTrue(raw(inner, it).startsWith(FieldSealer.PREFIX)) }
        assertEquals(true, store(inner).getBoolean("flag", false))
    }

    @Test
    fun legacyTextValuesOnATextStoreAreParsedAndSealed() {
        val inner = TextStore().also { it.values["flag"] = "true"; it.values["count"] = "12" }
        val s = store(inner)
        assertEquals(true, s.getBoolean("flag", false))
        assertEquals(12, s.getInt("count", 0))
        assertTrue(inner.values.getValue("flag").startsWith(FieldSealer.PREFIX))
        assertEquals(true, store(inner).getBoolean("flag", false))
    }

    @Test
    fun theExcludedKeysPassThroughUntouched() {
        val inner = StrictStore()
        val s = store(inner)
        s.putString("pin_hash", "sealed:1:abc")
        assertEquals("sealed:1:abc", inner.values["pin_hash"])
        assertEquals("sealed:1:abc", s.getString("pin_hash", ""))
    }

    @Test
    fun sealAllMigratesEveryLegacyKeyOnceAndLeavesTheExcludedOnes() {
        val inner = StrictStore().also { it.values["a"] = "x"; it.values["b"] = true; it.values["c"] = 3; it.values["d"] = 4L; it.values["pin_hash"] = "sealed:1:zz" }
        val s = store(inner)
        assertEquals(4, s.sealAll())
        assertEquals(0, s.sealAll())
        listOf("a", "b", "c", "d").forEach { assertTrue(raw(inner, it).startsWith(FieldSealer.PREFIX)) }
        assertEquals("sealed:1:zz", inner.values["pin_hash"])
        assertEquals(listOf("x", true, 3, 4L), listOf(s.getString("a", ""), s.getBoolean("b", false), s.getInt("c", 0), s.getLong("d", 0L)))
    }

    @Test
    fun whileLockedNothingIsWrittenInClearTextAndTheNewValueIsKeptInMemory() {
        val inner = StrictStore()
        unlocked = false
        val s = store(inner)
        s.putString("name", "Honor")
        assertNull("nothing written", inner.values["name"])
        assertEquals("Honor", s.getString("name", ""))
        unlocked = true
        assertEquals("Honor", s.getString("name", ""))
        assertTrue(raw(inner, "name").startsWith(FieldSealer.PREFIX))
    }

    @Test
    fun whileLockedASealedValueReadsAsMissingAndIsNeverOverwrittenByTheDefault() {
        val inner = StrictStore()
        store(inner).putString("name", "Honor")
        val before = raw(inner, "name")
        unlocked = false
        val s = store(inner)
        assertEquals("fallback", s.getString("name", "fallback"))
        assertEquals(before, raw(inner, "name"))
        unlocked = true
        assertEquals("Honor", s.getString("name", ""))
    }

    @Test
    fun removeClearAndContainsDelegate() {
        val inner = StrictStore()
        val s = store(inner)
        s.putString("a", "1"); s.putString("b", "2")
        assertTrue(s.contains("a"))
        assertEquals(listOf("a", "b"), s.keys("").sorted())
        s.remove("a")
        assertFalse(s.contains("a"))
        assertNull(s.getString("a", "").takeIf { it != "" })
        s.clear()
        assertTrue(inner.values.isEmpty())
    }

    @Test
    fun aValueMovedToAnotherKeyIsNotOpened() {
        val inner = StrictStore()
        val s = store(inner)
        s.putString("one", "secret")
        inner.values["two"] = inner.values.getValue("one")
        assertEquals("gone", s.getString("two", "gone"))
        assertNotNull(inner.values["two"])
    }

    @Test
    fun healthCountsWhatOpensWhatDoesNotAndWhatIsStillClearText() {
        val inner = StrictStore()
        val old = SealedValuesKvStore(inner, { FieldSealer(ByteArray(32) { 9 }) }, excluded, log = {})
        old.putString("under_old_key", "x")
        val current = store(inner)
        current.putString("under_current_key", "y")
        inner.values["still_clear"] = "z"
        val health = current.health()
        assertEquals(listOf(1, 1, 1), listOf(health.openable, health.unreadable, health.clearText))
        assertEquals("only the names of what cannot be opened, never a value", listOf("under_old_key"), health.unreadableKeys)
    }
}
