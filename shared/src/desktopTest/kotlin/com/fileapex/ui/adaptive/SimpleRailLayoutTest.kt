package com.fileapex.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleRailLayoutTest {

    private val heights = mapOf(
        SimpleRailEntry.LocalFiles to 100,
        SimpleRailEntry.Browse to 140,
        SimpleRailEntry.Clipboard to 100,
        SimpleRailEntry.DeviceManagement to 140,
        SimpleRailEntry.Settings to 100
    )
    private val more = 100

    @Test
    fun everythingStaysWhenThereIsRoom() {
        assertTrue(hiddenRailEntries(heights, more, available = 580).isEmpty())
    }

    @Test
    fun shortWindowKeepsPrimaryDestinationsAndMovesTheRestToMore() {
        val hidden = hiddenRailEntries(heights, more, available = 380)
        assertEquals(
            setOf(SimpleRailEntry.Settings, SimpleRailEntry.Clipboard, SimpleRailEntry.DeviceManagement),
            hidden
        )
    }

    @Test
    fun slightShortfallHidesOnlyTheLastActionsAndKeepsSettingsVisible() {
        val hidden = hiddenRailEntries(heights, more, available = 570)
        assertEquals(setOf(SimpleRailEntry.DeviceManagement), hidden)
    }

    @Test
    fun tinyWindowStillLeavesAWayToReachEverything() {
        val hidden = hiddenRailEntries(heights, more, available = 50)
        assertEquals(SimpleRailEntry.entries.toSet(), hidden)
    }
}
