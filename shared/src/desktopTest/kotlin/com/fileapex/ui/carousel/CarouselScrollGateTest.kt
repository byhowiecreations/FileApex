package com.fileapex.ui.carousel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselScrollGateTest {
    /** Events 16 ms apart whose size decays like momentum scrolling; returns the cards moved. */
    private fun swipe(gate: CarouselScrollGate, startMs: Long, first: Float, sign: Int = 1, events: Int = 80): Int {
        var size = first
        var moved = 0
        for (i in 0 until events) {
            moved += gate.onScroll(sign * size, startMs + i * 16L)
            size *= 0.94f
        }
        return moved
    }

    @Test
    fun aLongMomentumSwipeMovesAtMostTwoCards() {
        val moved = swipe(CarouselScrollGate(), 0L, 8f)
        assertTrue("moved $moved", kotlin.math.abs(moved) in 1..2)
    }

    @Test
    fun aGentleSwipeMovesOneCard() {
        val moved = swipe(CarouselScrollGate(), 0L, 1.2f, events = 6)
        assertEquals(1, moved)
    }

    @Test
    fun directionFollowsTheScroll() {
        assertEquals(-1, swipe(CarouselScrollGate(), 0L, 1.2f, sign = -1, events = 6))
    }

    @Test
    fun aSecondSwipeAfterAPauseMoves() {
        val gate = CarouselScrollGate()
        swipe(gate, 0L, 8f)
        val again = swipe(gate, 3000L, 2f, events = 6)
        assertTrue(again != 0)
    }

    @Test
    fun aNewFlickDuringTheTailOfMomentumStillMoves() {
        val gate = CarouselScrollGate()
        swipe(gate, 0L, 8f, events = 40)
        // the tail is now tiny; a fresh hard flick 700 ms later, with no pause in the event stream
        var moved = 0
        var size = 6f
        for (i in 0 until 30) {
            moved += gate.onScroll(size, 40 * 16L + 700L + i * 16L)
            size *= 0.94f
        }
        assertTrue("moved $moved", moved != 0)
    }

    @Test
    fun flippingDirectionStartsANewSwipe() {
        val gate = CarouselScrollGate()
        swipe(gate, 0L, 8f, events = 30)
        val back = gate.onScroll(-3f, 30 * 16L + 400L)
        assertEquals(-1, back)
    }
}
