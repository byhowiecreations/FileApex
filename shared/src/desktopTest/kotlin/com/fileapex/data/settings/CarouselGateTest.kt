package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselGateTest {
    @Test
    fun needsMacAndThreePeers() {
        assertTrue(CarouselGate.available("Mac", 3))
        assertTrue(CarouselGate.available("Mac", 9))
        assertFalse(CarouselGate.available("Mac", 2))
        assertFalse(CarouselGate.available("Windows", 5))
        assertFalse(CarouselGate.available("Linux", 5))
        assertFalse(CarouselGate.available("Android", 5))
    }

    @Test
    fun unavailableCarouselShowsTheFallbackAndOtherThemesAreUntouched() {
        assertEquals(AppTheme.FLUX_GLASS, CarouselGate.effective(AppTheme.CAROUSEL, false, AppTheme.FLUX_GLASS))
        assertEquals(AppTheme.CAROUSEL, CarouselGate.effective(AppTheme.CAROUSEL, true, AppTheme.FLUX_GLASS))
        assertEquals(AppTheme.FREESTYLE, CarouselGate.effective(AppTheme.FREESTYLE, false, AppTheme.FLUX_GLASS))
    }

    @Test
    fun fallbackNeverLoopsBackToCarousel() {
        assertEquals(AppTheme.DEFAULT, CarouselGate.effective(AppTheme.CAROUSEL, false, AppTheme.CAROUSEL))
    }
}
