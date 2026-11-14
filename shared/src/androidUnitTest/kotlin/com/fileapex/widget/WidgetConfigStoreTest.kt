package com.fileapex.widget

import android.content.Context
import com.fileapex.domain.widget.CachedBatteryReading
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetConfig
import com.fileapex.domain.widget.WidgetKind
import com.fileapex.domain.widget.WidgetTargetBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fileapex_widgets_test", Context.MODE_PRIVATE).edit().clear().commit()
        store = WidgetConfigStore(context, prefsName = "fileapex_widgets_test")
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
}
