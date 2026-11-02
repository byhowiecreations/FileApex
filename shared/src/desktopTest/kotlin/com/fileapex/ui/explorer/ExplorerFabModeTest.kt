package com.fileapex.ui.explorer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerFabModeTest {
    @Test
    fun nothingSelectedShowsNoButton() {
        assertEquals(ExplorerFabMode.None, ExplorerFabMode.of(picking = false, selecting = true, selectedCount = 0, multiCopying = false))
        assertEquals(ExplorerFabMode.None, ExplorerFabMode.of(picking = true, selecting = true, selectedCount = 0, multiCopying = false))
    }

    @Test
    fun aSelectionInTheFilesBrowserOffersCopyAndSendTo() {
        assertEquals(ExplorerFabMode.Copy, ExplorerFabMode.of(picking = false, selecting = true, selectedCount = 2, multiCopying = false))
    }

    @Test
    fun aSelectionWhilePickingFilesForAFriendOffersSendWhateverTheShellShows() {
        assertEquals(ExplorerFabMode.Pick, ExplorerFabMode.of(picking = true, selecting = true, selectedCount = 1, multiCopying = false))
    }

    @Test
    fun noButtonWhileACopyToDevicesIsRunningOrOutsideSelection() {
        assertEquals(ExplorerFabMode.None, ExplorerFabMode.of(picking = false, selecting = true, selectedCount = 2, multiCopying = true))
        assertEquals(ExplorerFabMode.None, ExplorerFabMode.of(picking = true, selecting = false, selectedCount = 2, multiCopying = false))
    }
}
