package com.fileapex.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExplorerViewModeTest {
    @Test
    fun withoutSplitTheModeOnlyFlipsBetweenListAndGrid() {
        assertEquals(ExplorerViewMode.Grid, ExplorerViewMode.List.next(includeSplit = false))
        assertEquals(ExplorerViewMode.List, ExplorerViewMode.Grid.next(includeSplit = false))
    }

    @Test
    fun aSavedSplitModeLeavesSplitOnTheFirstToggleWhenSplitIsNotAllowed() {
        assertEquals(ExplorerViewMode.List, ExplorerViewMode.Split.next(includeSplit = false))
    }

    @Test
    fun noModeReachesSplitWhenSplitIsNotAllowed() {
        ExplorerViewMode.entries.forEach { assertNotEquals(ExplorerViewMode.Split, it.next(includeSplit = false)) }
    }

    @Test
    fun withSplitTheModeCyclesListGridSplit() {
        assertEquals(ExplorerViewMode.Grid, ExplorerViewMode.List.next(includeSplit = true))
        assertEquals(ExplorerViewMode.Split, ExplorerViewMode.Grid.next(includeSplit = true))
        assertEquals(ExplorerViewMode.List, ExplorerViewMode.Split.next(includeSplit = true))
    }
}
