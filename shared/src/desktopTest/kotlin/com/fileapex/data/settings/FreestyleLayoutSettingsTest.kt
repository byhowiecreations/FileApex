package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Freestyle layout settings promise, whoever owns the code that keeps them. */
class FreestyleLayoutSettingsTest {
    private val kv = MapKvStore()
    private fun settings() = BaseAppSettings(kv)
    private val modes = FreestyleLayoutMode.entries

    @Test
    fun optionsPositionIsKeptPerModeAndSurvivesARestart() {
        val first = settings()
        first.setFreestyleOptionsPosition(FreestyleLayoutMode.CARDS_HORIZONTAL, 1f, 2f)
        first.setFreestyleOptionsPosition(FreestyleLayoutMode.TILES, 3f, 4f)
        val second = settings()
        assertEquals(1f, second.freestyleCardOptionsPosX.value)
        assertEquals(2f, second.freestyleCardOptionsPosY.value)
        assertEquals(3f, second.freestyleTileOptionsPosX.value)
        assertEquals(4f, second.freestyleTileOptionsPosY.value)
        assertEquals(1f, second.freestyleCardVerticalOptionsPosX.value)
        first.setFreestyleOptionsPosition(FreestyleLayoutMode.TILES, null, null)
        assertNull(settings().freestyleTileOptionsPosX.value)
    }

    @Test
    fun theBooleanOverloadMeansHorizontalCardsOrTiles() {
        val s = settings()
        s.setFreestyleOptionsPosition(true, 5f, 6f)
        s.setFreestyleOptionsPosition(false, 7f, 8f)
        assertEquals(5f, s.freestyleCardOptionsPosX.value)
        assertEquals(7f, s.freestyleTileOptionsPosX.value)
    }

    @Test
    fun nodeOffsetsAreKeptPerModeAndSurviveARestart() {
        val first = settings()
        first.setFreestyleNodeOffset(FreestyleLayoutMode.CARDS_HORIZONTAL, "fx-a", 1f, 2f)
        first.setFreestyleNodeOffset(FreestyleLayoutMode.CARDS_VERTICAL, "fx-a", 3f, 4f)
        first.setFreestyleNodeOffset(FreestyleLayoutMode.TILES, "fx-b", 5f, 6f)
        val second = settings()
        assertEquals(mapOf("fx-a" to (1f to 2f)), second.freestyleCardNodeOffsets.value)
        assertEquals(mapOf("fx-a" to (3f to 4f)), second.freestyleCardVerticalNodeOffsets.value)
        assertEquals(mapOf("fx-b" to (5f to 6f)), second.freestyleTileNodeOffsets.value)
    }

    @Test
    fun menuOrdersAreKeptPerModeAndTheVerticalOnesFallBackToTheHorizontalOnes() {
        val first = settings()
        first.setFreestyleMenuOrder(FreestyleLayoutMode.CARDS_HORIZONTAL, "fx-a", "files,settings")
        first.setFreestyleMenuOrder(FreestyleLayoutMode.TILES, "fx-a", "settings")
        val second = settings()
        assertEquals(mapOf("fx-a" to "files,settings"), second.freestyleCardMenuOrders.value)
        assertEquals(mapOf("fx-a" to "files,settings"), second.freestyleCardVerticalMenuOrders.value)
        assertEquals(mapOf("fx-a" to "settings"), second.freestyleTileMenuOrders.value)
        first.setFreestyleMenuOrder(FreestyleLayoutMode.CARDS_VERTICAL, "fx-a", "files")
        assertEquals(mapOf("fx-a" to "files"), settings().freestyleCardVerticalMenuOrders.value)
    }

    @Test
    fun pinnedActionsAreAddedRemovedAndResetPerMode() {
        val first = settings()
        modes.forEach { first.setFreestylePinnedActionOffset(it, "files", 1f, 2f) }
        first.setFreestylePinnedActionOffset(FreestyleLayoutMode.TILES, "settings", 3f, 4f)
        val second = settings()
        assertEquals(mapOf("files" to (1f to 2f)), second.freestyleCardPinnedActions.value)
        assertEquals(mapOf("files" to (1f to 2f)), second.freestyleCardVerticalPinnedActions.value)
        assertEquals(mapOf("files" to (1f to 2f), "settings" to (3f to 4f)), second.freestyleTilePinnedActions.value)
        first.removeFreestylePinnedAction(FreestyleLayoutMode.TILES, "files")
        assertEquals(mapOf("settings" to (3f to 4f)), settings().freestyleTilePinnedActions.value)
        first.resetFreestylePinnedActions(FreestyleLayoutMode.CARDS_HORIZONTAL)
        assertTrue(settings().freestyleCardPinnedActions.value.isEmpty())
        assertEquals(mapOf("files" to (1f to 2f)), settings().freestyleCardVerticalPinnedActions.value)
    }

    @Test
    fun theOldOffsetStringsAreNoLongerImported() {
        kv.putString("freestyle_card_node_offsets", "fx-a|9.0|8.0")
        kv.putString("freestyle_card_vert_node_offsets", "fx-a|7.0|6.0")
        kv.putString("freestyle_tile_node_offsets", "fx-b|5.0|4.0")
        val s = settings()
        assertTrue(s.freestyleCardNodeOffsets.value.isEmpty())
        assertTrue(s.freestyleCardVerticalNodeOffsets.value.isEmpty())
        assertTrue(s.freestyleTileNodeOffsets.value.isEmpty())
    }

    @Test
    fun layoutModeTutorialAndOptionsOrderStartAtTheirDefaults() {
        val s = settings()
        assertEquals(FreestyleLayoutMode.DEFAULT, s.freestyleLayoutMode.value)
        assertEquals(false, s.freestyleEditTutorialShown.value)
        assertEquals("files,add_device,join_device,send_clipboard,check_batteries,settings", s.freestyleOptionsMenuOrder.value)
        s.setFreestyleLayoutMode(FreestyleLayoutMode.TILES)
        s.setFreestyleEditTutorialShown(true)
        s.setFreestyleOptionsMenuOrder("settings,files")
        val again = settings()
        assertEquals(FreestyleLayoutMode.TILES, again.freestyleLayoutMode.value)
        assertEquals(true, again.freestyleEditTutorialShown.value)
        assertEquals("settings,files", again.freestyleOptionsMenuOrder.value)
    }

    @Test
    fun resetToDefaultsForgetsEveryFreestyleChoice() {
        val s = settings()
        s.setFreestyleOptionsPosition(FreestyleLayoutMode.TILES, 1f, 2f)
        s.setFreestyleNodeOffset(FreestyleLayoutMode.TILES, "fx-a", 1f, 2f)
        s.setFreestyleMenuOrder(FreestyleLayoutMode.TILES, "fx-a", "files")
        s.setFreestylePinnedActionOffset(FreestyleLayoutMode.TILES, "files", 1f, 2f)
        s.setFreestyleLayoutMode(FreestyleLayoutMode.TILES)
        s.resetToDefaults()
        assertNull(s.freestyleTileOptionsPosX.value)
        assertTrue(s.freestyleTileNodeOffsets.value.isEmpty())
        assertTrue(s.freestyleTileMenuOrders.value.isEmpty())
        assertTrue(s.freestyleTilePinnedActions.value.isEmpty())
        assertEquals(FreestyleLayoutMode.DEFAULT, s.freestyleLayoutMode.value)
        val after = settings()
        assertNull(after.freestyleTileOptionsPosX.value)
        assertTrue(after.freestyleTileNodeOffsets.value.isEmpty())
    }
}
