package com.fileapex.ui.adaptive.simple

import com.fileapex.ui.adaptive.simple.OverviewCard.Browse
import com.fileapex.ui.adaptive.simple.OverviewCard.Clipboard
import com.fileapex.ui.adaptive.simple.OverviewCard.Devices
import com.fileapex.ui.adaptive.simple.OverviewCard.Locate
import com.fileapex.ui.adaptive.simple.OverviewCard.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleOverviewCardRowsTest {
    @Test
    fun shareSitsBesideTheDropAreaOnlyOnAWideDesktopWindow() {
        assertTrue("a default 616 dp window has about 480 dp of content", SimpleOverviewCardPolicy.shareBesideDrop(isDesktopHost = true, contentWidthDp = 480f))
        assertTrue(SimpleOverviewCardPolicy.shareBesideDrop(isDesktopHost = true, contentWidthDp = 960f))
        assertFalse(SimpleOverviewCardPolicy.shareBesideDrop(isDesktopHost = true, contentWidthDp = 400f))
        assertFalse(SimpleOverviewCardPolicy.shareBesideDrop(isDesktopHost = false, contentWidthDp = 1000f))
    }

    @Test
    fun theDropAreaKeepsTheLargerShareOfTheSplitRow() {
        val (drop, share) = SimpleOverviewCardPolicy.dropRowWeights()
        assertTrue("drop $drop must be at least twice share $share", drop >= 2f * share)
    }

    @Test
    fun devicesIsAlwaysACardWhateverTheNavigationPosition() {
        for (phone in listOf(true, false)) for (two in listOf(true, false)) for (beside in listOf(true, false)) {
            if (beside && !two) continue
            val cards = SimpleOverviewCardPolicy.cardRows(isPhonePeer = phone, twoColumns = two, shareBesideDrop = beside).flatten()
            assertEquals("phone=$phone two=$two beside=$beside", 1, cards.count { it == Devices })
        }
    }

    @Test
    fun desktopWithShareBesideDropPutsDevicesNextToBrowse() {
        assertEquals(
            listOf(listOf(Browse, Devices), listOf(Clipboard, Locate)),
            SimpleOverviewCardPolicy.cardRows(isPhonePeer = true, twoColumns = true, shareBesideDrop = true)
        )
        assertEquals(
            listOf(listOf(Browse, Devices), listOf(Clipboard)),
            SimpleOverviewCardPolicy.cardRows(isPhonePeer = false, twoColumns = true, shareBesideDrop = true)
        )
    }

    @Test
    fun androidLayoutsKeepTheirCurrentCards() {
        assertEquals(
            listOf(listOf(Browse, Share), listOf(Clipboard, Devices)),
            SimpleOverviewCardPolicy.cardRows(isPhonePeer = false, twoColumns = true, shareBesideDrop = false)
        )
        assertEquals(
            listOf(listOf(Browse, Share), listOf(Clipboard, Locate), listOf(Devices)),
            SimpleOverviewCardPolicy.cardRows(isPhonePeer = true, twoColumns = true, shareBesideDrop = false)
        )
        assertEquals(
            listOf(listOf(Browse), listOf(Share), listOf(Clipboard), listOf(Devices), listOf(Locate)),
            SimpleOverviewCardPolicy.cardRows(isPhonePeer = true, twoColumns = false, shareBesideDrop = false)
        )
    }
}
