package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class KineticOffsetsSizeTest {

    private class MapStore : SettingsKvStore {
        val map = mutableMapOf<String, Any>()
        var writes = 0
        override fun contains(key: String) = map.containsKey(key)
        override fun getBoolean(key: String, default: Boolean) = (map[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, default: String) = (map[key] as? String) ?: default
        override fun putString(key: String, value: String) {
            require(key.length <= 80) { "Key too long" }
            require(value.length <= 8192) { "Value too long" }
            writes++
            map[key] = value
        }
        override fun getInt(key: String, default: Int) = (map[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    @Test
    fun manyDevicesNeverBuildAValueNearTheLimit() {
        val store = MapStore()
        val settings = BaseAppSettings(store, existingInstall = false)
        repeat(400) {
            settings.setKineticNodeOffset("frac:land-med:fx-${UUID.randomUUID()}", it.toFloat(), 1.5f)
        }
        assertEquals(400, settings.kineticNodeOffsets.value.size)
        assertTrue(store.map.values.all { (it as? String)?.length ?: 0 < 64 })
    }

    @Test
    fun aDragWritesOnlyTheMovedEntriesAndOnlyWhenItEnds() {
        val store = MapStore()
        val settings = BaseAppSettings(store, existingInstall = false)
        repeat(50) { settings.setKineticNodeOffset("pos:old$it", 1f, 2f) }
        store.writes = 0
        val moved = mapOf("a" to Pair(1f, 2f), "b" to Pair(3f, 4f), "c" to Pair(5f, 6f))
        repeat(120) { settings.setKineticNodeOffsets(moved, persist = false) }
        assertEquals(0, store.writes)
        settings.setKineticNodeOffsets(moved, persist = true)
        assertEquals(3, store.writes)
    }

    @Test
    fun savedPositionsComeBackOnTheNextStart() {
        val store = MapStore()
        BaseAppSettings(store, existingInstall = false).setKineticNodeOffset("pos:exp:x", 7.5f, -3f)
        val restarted = BaseAppSettings(store, existingInstall = false)
        assertEquals(Pair(7.5f, -3f), restarted.kineticNodeOffsets.value["pos:exp:x"])
    }

    @Test
    fun theOldSingleStringIsImportedOnceWithoutItsDuplicatesAndRemoved() {
        val store = MapStore()
        store.map[BaseAppSettings.KEY_KINETIC_NODE_OFFSETS] = "exp_a|1.0|2.0;cmp_b|3.0|4.0;pos:c|5.0|6.0"
        store.map[BaseAppSettings.KEY_FREESTYLE_CARD_NODE_OFFSETS] = "d1|9.0|8.0"
        val settings = BaseAppSettings(store, existingInstall = false)
        assertEquals(Pair(1f, 2f), settings.kineticNodeOffsets.value["exp:a"])
        assertEquals(Pair(5f, 6f), settings.kineticNodeOffsets.value["pos:c"])
        assertFalse(store.map.containsKey(BaseAppSettings.KEY_KINETIC_NODE_OFFSETS))
        assertEquals(3, store.keys("kno:").size)
        assertEquals(Pair(9f, 8f), settings.freestyleCardNodeOffsets.value["d1"])
        assertEquals(Pair(9f, 8f), settings.freestyleCardVerticalNodeOffsets.value["d1"])
        assertFalse(store.map.containsKey(BaseAppSettings.KEY_FREESTYLE_CARD_NODE_OFFSETS))
    }

    @Test
    fun anOversizedOldStringStillLoads() {
        val store = MapStore()
        store.map[BaseAppSettings.KEY_KINETIC_NODE_OFFSETS] =
            (0 until 300).joinToString(";") { "pos:${UUID.randomUUID()}|1.5|2.5" }
        val settings = BaseAppSettings(store, existingInstall = false)
        assertEquals(300, settings.kineticNodeOffsets.value.size)
    }

    @Test
    fun resetRemovesEveryStoredEntry() {
        val store = MapStore()
        val settings = BaseAppSettings(store, existingInstall = false)
        settings.setKineticNodeOffset("pos:a", 1f, 2f)
        settings.resetKineticNodeOffsets()
        assertTrue(settings.kineticNodeOffsets.value.isEmpty())
        assertTrue(store.keys("kno:").isEmpty())
    }
}
