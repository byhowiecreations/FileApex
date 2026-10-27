package com.fileapex.ui.carousel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselMathTest {
    @Test
    fun wrapHasNoEnds() {
        assertEquals(0, CarouselMath.wrap(3, 3))
        assertEquals(2, CarouselMath.wrap(-1, 3))
        assertEquals(1, CarouselMath.wrap(-5, 3))
        assertEquals(1, CarouselMath.wrap(7, 3))
    }

    @Test
    fun shortestOffsetGoesTheShorterWayRound() {
        assertEquals(1, CarouselMath.shortestOffset(0, 4, 5))
        assertEquals(-1, CarouselMath.shortestOffset(3, 4, 5))
        assertEquals(0, CarouselMath.shortestOffset(2, 2, 5))
        assertEquals(-2, CarouselMath.shortestOffset(0, 2, 5))
        assertEquals(2, CarouselMath.shortestOffset(4, 2, 5))
    }

    @Test
    fun neighboursOfAThreeCardRowAreDifferentDevices() {
        val left = CarouselMath.shortestOffset(2, 0, 3)
        val right = CarouselMath.shortestOffset(1, 0, 3)
        assertEquals(-1, left)
        assertEquals(1, right)
    }

    @Test
    fun centreCardIsFullSizeAndSidesAreDimmer() {
        assertEquals(1.1f, CarouselMath.cardScale(0f), 0.0001f)
        assertEquals(1f, CarouselMath.cardAlpha(0f), 0.0001f)
        assertEquals(0.88f, CarouselMath.cardScale(1f), 0.0001f)
        assertTrue(CarouselMath.cardScale(0.5f) < CarouselMath.cardScale(0f))
        assertTrue(CarouselMath.cardScale(0.5f) > CarouselMath.cardScale(1f))
        assertTrue(CarouselMath.cardAlpha(1f) < CarouselMath.cardAlpha(0f))
        assertTrue(CarouselMath.cardScale(5f) >= 0.7f)
    }

    @Test
    fun dockMagnificationPeaksUnderThePointerAndFadesWithDistance() {
        val under = CarouselMath.dockScale(0f)
        val near = CarouselMath.dockScale(55f)
        val far = CarouselMath.dockScale(400f)
        assertEquals(1.925f, under, 0.0001f)
        assertTrue(under > near)
        assertTrue(near > far)
        assertEquals(1f, far, 0.001f)
    }
}
