package com.fileapex.ui.carousel

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock bar widens with the magnified icons, so an icon never leaves it at either end. */
class DockPillTest {
    private val size = 44f
    private val gap = 10f
    private val pad = 16f
    private val count = 5

    private class Edges(val left: Float, val right: Float)

    private fun edges(extra: List<Float>): Pair<List<Edges>, Float> {
        val growth = CarouselMath.dockPillGrowth(extra)
        val shifts = CarouselMath.dockShifts(extra)
        val pillWidth = 2 * pad + count * size + (count - 1) * gap + growth
        val items = extra.indices.map { i ->
            // The row has its left padding widened by half the growth, and the bar is centred, so it grows evenly on both sides.
            val centre = pad + growth / 2f + i * (size + gap) + size / 2f + shifts[i]
            Edges(centre - (size + extra[i]) / 2f, centre + (size + extra[i]) / 2f)
        }
        return items to pillWidth
    }

    @Test fun noMagnificationLeavesTheBarAsItWas() {
        val (items, width) = edges(List(count) { 0f })
        assertEquals(pad, items.first().left, 0.001f)
        assertEquals(width - pad, items.last().right, 0.001f)
        assertEquals(0f, CarouselMath.dockPillGrowth(List(count) { 0f }), 0f)
    }

    @Test fun iconsStayInsideTheBarWhateverTheMagnification() {
        val random = Random(7)
        repeat(500) {
            val extra = List(count) { random.nextFloat() * 40f }
            val (items, width) = edges(extra)
            assertTrue("left edge out of the bar: ${items.first().left}", items.first().left >= pad - 0.01f)
            assertTrue("right edge out of the bar: ${items.last().right} of $width", items.last().right <= width - pad + 0.01f)
        }
    }

    @Test fun theGapBetweenNeighboursIsKept() {
        val extra = listOf(5f, 30f, 12f, 0f, 7f)
        val (items, _) = edges(extra)
        for (i in 0 until count - 1) assertEquals(gap, items[i + 1].left - items[i].right, 0.001f)
    }

    @Test fun theBarGrowsByExactlyTheExtraSizeOfTheIcons() {
        val extra = listOf(0f, 10f, 33f, 10f, 0f)
        assertEquals(53f, CarouselMath.dockPillGrowth(extra), 0.001f)
    }
}
