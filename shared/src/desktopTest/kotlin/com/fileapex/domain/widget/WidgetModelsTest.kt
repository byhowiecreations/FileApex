package com.fileapex.domain.widget

import com.fileapex.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelsTest {

    @Test
    fun widgetKindsExposeExpectedCapacitiesAndBindingSupport() {
        assertEquals(4, WidgetKind.QUICK_ACTIONS.maxActions)
        assertEquals(0, WidgetKind.DEVICE_CARD_2X2.maxActions)
        assertEquals(0, WidgetKind.DROP_TARGET.maxActions)
        assertEquals(0, WidgetKind.BULLETIN_BOARD.maxActions)

        assertTrue(WidgetKind.QUICK_ACTIONS.supportsTargetBinding)
        assertTrue(WidgetKind.DEVICE_CARD_2X2.supportsTargetBinding)
        assertTrue(WidgetKind.DROP_TARGET.supportsTargetBinding)
        assertFalse(WidgetKind.BULLETIN_BOARD.supportsTargetBinding)

        assertEquals(WidgetKind.QUICK_ACTIONS, WidgetKind.fromWireId("quick_actions"))
        assertEquals(WidgetKind.QUICK_ACTIONS, WidgetKind.fromWireId("quick_1x1"))
        assertEquals(WidgetKind.QUICK_ACTIONS, WidgetKind.fromWireId("quick_2x1"))
        assertEquals(WidgetKind.QUICK_ACTIONS, WidgetKind.fromWireId("quick_4x1"))
    }

    @Test
    fun actionCatalogCoversAllSixteenTargetsAndRoundTripsWireIds() {
        val all = WidgetActionCatalog.allTargets()
        assertEquals(16, all.size)
        assertEquals(16, all.map { it.wireId }.toSet().size)
        all.forEach { target ->
            assertTrue(target.wireId.isNotBlank())
            assertTrue(target.labelKey.isNotBlank())
            assertEquals(target, WidgetActionCatalog.byWireId(target.wireId))
        }
        assertNull(WidgetActionCatalog.byWireId("non_existent_target"))
        assertNull(WidgetActionCatalog.byWireId(null))

        val mediaTargets = all.filter { it.requiresMediaAccess }.toSet()
        assertEquals(
            setOf(
                WidgetActionTarget.SEND_LAST_PHOTO,
                WidgetActionTarget.SEND_LAST_SCREENSHOT,
                WidgetActionTarget.SEND_LAST_VIDEO,
                WidgetActionTarget.SEND_LAST_DOWNLOAD
            ),
            mediaTargets
        )

        assertFalse(WidgetActionTarget.CHECK_BATTERY.supportsAllOnline)
        assertFalse(WidgetActionTarget.RING_PHONE.supportsAllOnline)
        assertFalse(WidgetActionTarget.PULL_CLIPBOARD.supportsAllOnline)
        assertFalse(WidgetActionTarget.BROWSE_DEVICE.supportsAllOnline)
        assertTrue(WidgetActionTarget.SEND_FILE.supportsAllOnline)
        assertTrue(WidgetActionTarget.SEND_CLIPBOARD.supportsAllOnline)
    }

    @Test
    fun targetBindingCodecRoundTripsAndFallsBackSafely() {
        assertEquals(
            WidgetTargetBinding.SpecificDevice("fx-peer-1"),
            WidgetTargetBindingCodec.decode(
                WidgetTargetBindingCodec.encode(WidgetTargetBinding.SpecificDevice("fx-peer-1"))
            )
        )
        assertEquals(
            WidgetTargetBinding.AllOnline,
            WidgetTargetBindingCodec.decode(WidgetTargetBindingCodec.encode(WidgetTargetBinding.AllOnline))
        )
        assertEquals(
            WidgetTargetBinding.AskEachTime,
            WidgetTargetBindingCodec.decode(WidgetTargetBindingCodec.encode(WidgetTargetBinding.AskEachTime))
        )
        assertEquals(WidgetTargetBinding.AskEachTime, WidgetTargetBindingCodec.decode(null))
        assertEquals(WidgetTargetBinding.AskEachTime, WidgetTargetBindingCodec.decode(""))
        assertEquals(WidgetTargetBinding.AskEachTime, WidgetTargetBindingCodec.decode("device:   "))
        assertEquals(WidgetTargetBinding.AskEachTime, WidgetTargetBindingCodec.decode("garbage"))
    }

    @Test
    fun configNormalizesActionCountToWidgetCapacity() {
        val overflow = WidgetConfig(
            appWidgetId = 42,
            kind = WidgetKind.QUICK_ACTIONS,
            binding = WidgetTargetBinding.AllOnline,
            actions = listOf(
                WidgetActionTarget.SEND_LAST_PHOTO,
                WidgetActionTarget.SEND_CLIPBOARD,
                WidgetActionTarget.RING_PHONE,
                WidgetActionTarget.CHECK_BATTERY,
                WidgetActionTarget.BACKUP_NOW
            )
        )
        assertEquals(4, overflow.normalizedActions().size)
        assertEquals(
            listOf(
                WidgetActionTarget.SEND_LAST_PHOTO,
                WidgetActionTarget.SEND_CLIPBOARD,
                WidgetActionTarget.RING_PHONE,
                WidgetActionTarget.CHECK_BATTERY
            ),
            overflow.normalizedActions()
        )

        val twoActions = WidgetConfig(
            appWidgetId = 45,
            kind = WidgetKind.QUICK_ACTIONS,
            binding = WidgetTargetBinding.AllOnline,
            actions = listOf(WidgetActionTarget.SEND_LAST_PHOTO, WidgetActionTarget.SEND_CLIPBOARD)
        )
        assertEquals(2, twoActions.normalizedActions().size)

        val emptyDefault = WidgetConfig(
            appWidgetId = 43,
            kind = WidgetKind.QUICK_ACTIONS,
            binding = WidgetTargetBinding.AskEachTime,
            actions = emptyList()
        )
        assertEquals(1, emptyDefault.normalizedActions().size)

        val nonAction = WidgetConfig(
            appWidgetId = 44,
            kind = WidgetKind.BULLETIN_BOARD,
            actions = listOf(WidgetActionTarget.OPEN_BULLETIN)
        )
        assertEquals(emptyList<WidgetActionTarget>(), nonAction.normalizedActions())
    }

    @Test
    fun batteryFormatterHandlesNullOutOfRangeAndValidReadingsWithoutEmDash() {
        assertNull(WidgetBatteryFormatter.formatPillText(null))
        assertNull(
            WidgetBatteryFormatter.formatPillText(
                CachedBatteryReading(levelPercent = -1, recordedAtEpochMs = 1_700_000_000_000L)
            )
        )
        assertNull(
            WidgetBatteryFormatter.formatPillText(
                CachedBatteryReading(levelPercent = 101, recordedAtEpochMs = 1_700_000_000_000L)
            )
        )
        val formatted = WidgetBatteryFormatter.formatPillText(
            reading = CachedBatteryReading(levelPercent = 82, recordedAtEpochMs = 1_700_000_000_000L),
            zoneId = TimeUtils.DEFAULT_ZONE_ID
        )
        assertNotNull(formatted)
        assertTrue(formatted!!.contains("82%"))
        assertFalse(formatted.contains("\u2014"))
    }
}
