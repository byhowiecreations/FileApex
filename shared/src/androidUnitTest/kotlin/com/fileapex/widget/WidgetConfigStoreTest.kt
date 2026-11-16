package com.fileapex.widget

import android.content.Context
import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import com.fileapex.domain.widget.WidgetTargetBindingCodec
import com.fileapex.domain.widget.CachedBatteryReading
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetConfig
import com.fileapex.domain.widget.WidgetKind
import com.fileapex.domain.widget.WidgetTargetBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfigStoreTest {

    private lateinit var store: WidgetConfigStore
    private var sealer: FieldSealer? = null
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("fileapex_widgets_test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        SealedText.sealerProvider = { sealer }
        val context = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
        store = WidgetConfigStore(context, prefsName = "fileapex_widgets_test")
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
    }

    @Test
    fun savesLoadsAndDeletesWidgetConfigPerId() {
        assertFalse(store.hasConfig(101))
        val config = WidgetConfig(
            appWidgetId = 101,
            kind = WidgetKind.QUICK_ACTIONS,
            binding = WidgetTargetBinding.SpecificDevice("fx-device-a"),
            actions = listOf(WidgetActionTarget.SEND_LAST_PHOTO, WidgetActionTarget.RING_PHONE)
        )
        store.save(config)
        assertTrue(store.hasConfig(101))
        assertEquals(config, store.load(101))

        store.remove(101)
        assertFalse(store.hasConfig(101))
        assertNull(store.load(101))
    }

    @Test
    fun cachesBatteryReadingAndClearsRemovedDevices() {
        store.save(
            WidgetConfig(
                appWidgetId = 201,
                kind = WidgetKind.DEVICE_CARD_2X2,
                binding = WidgetTargetBinding.SpecificDevice("fx-keep")
            )
        )
        store.save(
            WidgetConfig(
                appWidgetId = 202,
                kind = WidgetKind.DEVICE_CARD_2X2,
                binding = WidgetTargetBinding.SpecificDevice("fx-gone")
            )
        )
        store.save(
            WidgetConfig(
                appWidgetId = 203,
                kind = WidgetKind.DROP_TARGET,
                binding = WidgetTargetBinding.SpecificDevice("fx-gone")
            )
        )

        val reading = CachedBatteryReading(levelPercent = 67, recordedAtEpochMs = 1_700_000_000_000L, chargingState = "USB")
        store.saveBatteryReading("fx-keep", reading)
        store.saveBatteryReading("fx-gone", reading)
        assertEquals(reading, store.loadBatteryReading("fx-keep"))

        store.retainPairedDevices(setOf("fx-keep"))

        assertEquals(WidgetTargetBinding.SpecificDevice("fx-keep"), store.load(201)?.binding)
        assertEquals(WidgetTargetBinding.AskEachTime, store.load(202)?.binding)
        assertEquals(WidgetTargetBinding.AskEachTime, store.load(203)?.binding)
        assertEquals(reading, store.loadBatteryReading("fx-keep"))
        assertNull(store.loadBatteryReading("fx-gone"))
    }

    private val binding = WidgetTargetBinding.SpecificDevice("fx-device-a")
    private val config = WidgetConfig(
        appWidgetId = 301,
        kind = WidgetKind.QUICK_ACTIONS,
        binding = binding,
        actions = listOf(WidgetActionTarget.SEND_LAST_PHOTO, WidgetActionTarget.RING_PHONE)
    )

    @Test
    fun theConfigAndTheBatteryReadingAreStoredSealed() {
        store.save(config)
        store.saveBatteryReading("fx-device-a", CachedBatteryReading(levelPercent = 67, recordedAtEpochMs = 1_700_000_000_000L, chargingState = "USB"))
        val stored = prefs.all.values.map { it.toString() }
        assertTrue("every stored value must be sealed: $stored", stored.isNotEmpty() && stored.all { it.startsWith(FieldSealer.PREFIX) })
        assertFalse(stored.any { "fx-device-a" in it || "USB" in it || "67" == it })
        assertEquals(config, store.load(301))
        assertEquals(listOf(301), store.listAppWidgetIds())
    }

    @Test
    fun aPlainConfigAndBatteryReadingFromAnOlderVersionAreReadAndSealedInPlace() {
        prefs.edit()
            .putString("cfg_301_kind", config.kind.wireId)
            .putString("cfg_301_bind", WidgetTargetBindingCodec.encode(binding))
            .putString("cfg_301_acts", config.actions.joinToString(",") { it.wireId })
            .putInt("bat_fx-device-a_pct", 67)
            .putLong("bat_fx-device-a_ts", 1_700_000_000_000L)
            .putString("bat_fx-device-a_chg", "USB")
            .commit()

        assertEquals(config, store.load(301))
        assertEquals(CachedBatteryReading(67, 1_700_000_000_000L, "USB"), store.loadBatteryReading("fx-device-a"))
        assertTrue(prefs.all.values.map { it.toString() }.all { it.startsWith(FieldSealer.PREFIX) })
    }

    @Test
    fun withNoKeyNothingIsWrittenInClearAndWhatIsStoredIsLeftAlone() {
        store.save(config)
        val before = prefs.all.toMap()
        sealer = null
        store.save(config.copy(appWidgetId = 302))
        store.saveBatteryReading("fx-other", CachedBatteryReading(50, 1_700_000_000_000L, "AC"))
        assertEquals(before, prefs.all.toMap())
        assertNull(store.load(301))
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        assertEquals(config, store.load(301))
    }

    @Test
    fun removingAWidgetDeletesItsSealedEntries() {
        store.save(config)
        store.remove(301)
        assertTrue(prefs.all.isEmpty())
    }
}
