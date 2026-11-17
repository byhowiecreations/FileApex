package com.fileapex.ui.explorer

import com.fileapex.presentation.ExplorerSortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerPaneFitTest {
    @Test
    fun `left pane takes exactly what the longest name needs when that is reasonable`() {
        assertEquals(0.3f, ExplorerPaneFit.fraction(longestNameDp = 180f, chromeDp = 60f, totalWidthDp = 800f), 0.0001f)
    }

    @Test
    fun `a very long name never takes more than the cap`() {
        assertEquals(ExplorerPaneFit.MAX_FRACTION, ExplorerPaneFit.fraction(900f, 60f, 1000f), 0.0001f)
    }

    @Test
    fun `short names never shrink the pane below the floor`() {
        assertEquals(ExplorerPaneFit.MIN_FRACTION, ExplorerPaneFit.fraction(20f, 60f, 1000f), 0.0001f)
    }

    @Test
    fun `an unmeasured window keeps the default fraction`() {
        assertEquals(ExplorerPaneFit.DEFAULT_FRACTION, ExplorerPaneFit.fraction(180f, 60f, 0f), 0.0001f)
    }

    @Test
    fun `header clicks map to the existing sort modes`() {
        assertEquals(ExplorerSortMode.Name, ExplorerDetailsColumn.Name.sortMode)
        assertEquals(ExplorerSortMode.Size, ExplorerDetailsColumn.Size.sortMode)
        assertEquals(ExplorerSortMode.Date, ExplorerDetailsColumn.Modified.sortMode)
    }
}
