package com.fileapex.data.seal

import com.fileapex.data.db.seal.FieldSealer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SealedStringsTest {
    private class MapSlots : StringSlots {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private var sealer: FieldSealer? = null
    private val slots = MapSlots()
    private val strings = SealedStrings(slots, "identity")

    @Before
    fun setUp() {
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        SealedText.sealerProvider = { sealer }
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
    }

    @Test
    fun aStoredValueIsSealedAndReadsBack() {
        assertTrue(strings.put("device_name", "Kitchen Tablet"))
        assertFalse(slots.map.getValue("device_name").contains("Kitchen"))
        assertEquals("Kitchen Tablet", strings.get("device_name"))
    }

    @Test
    fun aPlainValueFromAnOlderVersionIsReadAndSealedInPlace() {
        slots.put("device_name", "Old Phone")
        assertEquals("Old Phone", strings.get("device_name"))
        assertFalse(slots.map.getValue("device_name").contains("Old Phone"))
        assertEquals("Old Phone", strings.get("device_name"))
    }

    @Test
    fun aValueMovedToAnotherKeyDoesNotOpen() {
        strings.put("a", "secret")
        slots.put("b", slots.map.getValue("a"))
        assertNull(strings.get("b"))
    }

    @Test
    fun aLockedKeyWritesNothingAndKeepsWhatIsStored() {
        strings.put("a", "kept")
        val before = slots.map.getValue("a")
        sealer = null
        assertFalse(strings.put("a", "new"))
        assertNull(strings.get("a"))
        assertEquals(before, slots.map.getValue("a"))
    }

    @Test
    fun aMissingValueIsNull() {
        assertNull(strings.get("nothing"))
    }
}
