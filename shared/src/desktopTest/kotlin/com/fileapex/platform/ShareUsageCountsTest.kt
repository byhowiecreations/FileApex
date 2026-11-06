package com.fileapex.platform

import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedStrings
import com.fileapex.data.seal.SealedText
import com.fileapex.data.seal.StringSlots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShareUsageCountsTest {
    private class MapSlots : StringSlots {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private var sealer: FieldSealer? = null
    private val slots = MapSlots()
    private val counts get() = ShareUsageCounts(SealedStrings(slots, "share-usage"), slots)

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
    fun recordingCountsPerDeviceAndNothingIsStoredInTheClear() {
        counts.record("dev-aaa")
        counts.record("dev-aaa")
        counts.record("dev-bbb")
        assertEquals(2, counts.count("dev-aaa"))
        assertEquals(1, counts.count("dev-bbb"))
        assertEquals(0, counts.count("dev-ccc"))
        assertTrue(slots.map.values.none { it.contains("dev-aaa") })
        assertTrue(slots.map.keys.none { it.contains("dev-aaa") })
    }

    @Test
    fun clearingForgetsOneDevice() {
        counts.record("dev-aaa")
        counts.record("dev-bbb")
        counts.clear("dev-aaa")
        assertEquals(0, counts.count("dev-aaa"))
        assertEquals(1, counts.count("dev-bbb"))
    }

    @Test
    fun theOldOneKeyPerDeviceCountsMoveIntoTheSealedBlob() {
        slots.put("share_count_dev-old", "7")
        assertEquals(7, counts.count("dev-old"))
        counts.record("dev-old")
        assertEquals(8, counts.count("dev-old"))
        assertFalse(slots.map.keys.any { it.startsWith("share_count_") })
        assertTrue(slots.map.values.none { it.contains("dev-old") })
    }

    @Test
    fun aLockedKeyKeepsTheOldCountsAndDropsNewOnes() {
        slots.put("share_count_dev-old", "7")
        sealer = null
        counts.record("dev-old")
        assertEquals("7", slots.map["share_count_dev-old"])
    }
}
