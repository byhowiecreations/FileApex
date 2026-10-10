package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeTraitsTest {

    @Test
    fun traitsMatchOriginalThemeSets() {
        for (t in AppTheme.entries) {
            assertEquals(
                t == AppTheme.FLUX_GLASS || t == AppTheme.KINETIC_SPHERE || t == AppTheme.FREESTYLE,
                t.traits.glassChrome,
            )
            assertEquals(
                t == AppTheme.FLUX_GLASS || t == AppTheme.KINETIC_SPHERE,
                t.traits.glassNotesSurfaces,
            )
            assertEquals(
                t == AppTheme.FLUX_GLASS || t == AppTheme.FREESTYLE,
                t.traits.reorderAccent,
            )
            assertEquals(
                t == AppTheme.KINETIC_SPHERE || t == AppTheme.FREESTYLE,
                t.traits.spatialHome,
            )
            assertEquals(
                t == AppTheme.SIMPLE || t == AppTheme.CLEAN || t == AppTheme.FLUX_GLASS,
                t.traits.desktopHoverPopOver,
            )
            assertEquals(t == AppTheme.FLUX_GLASS, t.traits.fluxSurfaces)
            assertEquals(t == AppTheme.KINETIC_SPHERE, t.traits.orbitalHome)
            assertEquals(t == AppTheme.FREESTYLE, t.traits.canvasHome)
            assertSame(t.traits, t.traits)
        }
    }
}