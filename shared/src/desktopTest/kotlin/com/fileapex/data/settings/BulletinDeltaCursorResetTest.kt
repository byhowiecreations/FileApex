package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class BulletinDeltaCursorResetTest {

    private class MapStore : SettingsKvStore {
        val map = mutableMapOf<String, Any>()
        override fun contains(key: String) = map.containsKey(key)
        override fun getBoolean(key: String, default: Boolean) = (map[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, default: String) = (map[key] as? String) ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int) = (map[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    @Test
    fun clearingTheClusterForgetsHowFarEveryPeerWasPulledSoARejoinPullsTheHistoryAgain() {
        val store = MapStore()
        val settings = BaseAppSettings(store)
        settings.setBulletinDeltaCursor("mac", 5_000L)
        settings.setBulletinDeltaCursor("fold", 7_000L)
        settings.setBulletinSeenAtEpochMs(9_000L)

        settings.clearBulletinDeltaCursors()

        assertEquals(0L, settings.bulletinDeltaCursor("mac"))
        assertEquals(0L, settings.bulletinDeltaCursor("fold"))
        assertEquals(9_000L, settings.bulletinSeenAtEpochMs.value)
    }
}
