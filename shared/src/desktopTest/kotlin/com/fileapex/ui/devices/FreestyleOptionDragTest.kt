package com.fileapex.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dragging an Options row with a mouse drifts up and down before it leaves the menu; the dragged row must stay the same one. */
class FreestyleOptionDragTest {
    private val rowHeight = 40f
    private val order = FreestyleOptionAction.DEFAULT_ORDER

    @Test
    fun smallDriftDoesNotSwap() {
        assertNull(swapOptionsDuringDrag(order, order, 0, 15f, rowHeight))
        assertNull(swapOptionsDuringDrag(order, order, 2, -15f, rowHeight))
    }

    @Test
    fun dragDownPastHalfARowSwapsWithTheNextAndFollowsTheRow() {
        val swap = swapOptionsDuringDrag(order, order, 0, 25f, rowHeight)!!
        assertEquals(1, swap.index)
        assertEquals(FreestyleOptionAction.FILES, swap.order[1])
        assertEquals(FreestyleOptionAction.ADD_DEVICE, swap.order[0])
        assertEquals(25f - rowHeight, swap.offsetY, 0.001f)
    }

    @Test
    fun theDraggedRowStaysTheSameThroughSeveralSwapsWhenTheListIsReadFresh() {
        var current = order
        var idx = 0
        var offset = 0f
        // The finger drifts down two rows, then a little back up; each step reads the list as it is now.
        for (delta in listOf(25f, 45f, -30f)) {
            offset += delta
            val swap = swapOptionsDuringDrag(current, current, idx, offset, rowHeight)
            if (swap != null) { current = swap.order; idx = swap.index; offset = swap.offsetY }
        }
        assertEquals(FreestyleOptionAction.FILES, current[idx])
    }

    @Test
    fun nothingMovesPastTheEnds() {
        assertNull(swapOptionsDuringDrag(order, order, 0, -50f, rowHeight))
        assertNull(swapOptionsDuringDrag(order, order, order.lastIndex, 50f, rowHeight))
    }

    @Test
    fun pinnedEntriesAreSkippedBecauseOnlyTheVisibleListDecidesNeighbors() {
        val visible = order.filter { it != FreestyleOptionAction.ADD_DEVICE }
        val swap = swapOptionsDuringDrag(order, visible, 0, 25f, rowHeight)!!
        // FILES swaps with JOIN_DEVICE, the next visible entry, in the full order.
        assertEquals(FreestyleOptionAction.JOIN_DEVICE, swap.order[0])
        assertEquals(FreestyleOptionAction.FILES, swap.order[2])
    }
}
