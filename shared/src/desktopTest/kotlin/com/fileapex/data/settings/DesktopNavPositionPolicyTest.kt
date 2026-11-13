package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNavPositionPolicyTest {

    @Test
    fun supportsNavPositionOnSimpleKineticCleanAndFluxButNotFreestyleOrCarousel() {
        assertTrue(DesktopNavPositionPolicy.supportsTheme(AppTheme.SIMPLE))
        assertTrue(DesktopNavPositionPolicy.supportsTheme(AppTheme.KINETIC_SPHERE))
        assertTrue(DesktopNavPositionPolicy.supportsTheme(AppTheme.CLEAN))
        assertTrue(DesktopNavPositionPolicy.supportsTheme(AppTheme.FLUX_GLASS))
        assertFalse(DesktopNavPositionPolicy.supportsTheme(AppTheme.FREESTYLE))
        assertFalse(DesktopNavPositionPolicy.supportsTheme(AppTheme.CAROUSEL))
    }

    @Test
    fun desktopAppliesLeftBottomRightAcrossCompactAndWideWindowsForAllSupportedThemes() {
        val supported = listOf(
            AppTheme.SIMPLE,
            AppTheme.KINETIC_SPHERE,
            AppTheme.CLEAN,
            AppTheme.FLUX_GLASS
        )
        supported.forEach { theme ->
            DesktopNavPosition.entries.forEach { chosen ->
                assertEquals(
                    "Compact desktop should obey $chosen in $theme",
                    chosen,
                    DesktopNavPositionPolicy.resolve(
                        theme = theme,
                        isDesktop = true,
                        windowIsWide = false,
                        desktopPosition = chosen
                    )
                )
                assertEquals(
                    "Wide desktop should obey $chosen in $theme",
                    chosen,
                    DesktopNavPositionPolicy.resolve(
                        theme = theme,
                        isDesktop = true,
                        windowIsWide = true,
                        desktopPosition = chosen
                    )
                )
            }
        }
    }

    @Test
    fun nonDesktopUsesWindowWidthInsteadOfDesktopNavPosition() {
        listOf(AppTheme.SIMPLE, AppTheme.KINETIC_SPHERE, AppTheme.CLEAN, AppTheme.FLUX_GLASS).forEach { theme ->
            assertEquals(
                DesktopNavPosition.Bottom,
                DesktopNavPositionPolicy.resolve(
                    theme = theme,
                    isDesktop = false,
                    windowIsWide = false,
                    desktopPosition = DesktopNavPosition.Right
                )
            )
            assertEquals(
                DesktopNavPosition.Left,
                DesktopNavPositionPolicy.resolve(
                    theme = theme,
                    isDesktop = false,
                    windowIsWide = true,
                    desktopPosition = DesktopNavPosition.Bottom
                )
            )
        }
    }

    @Test
    fun headerToggleShownOnlyOnDesktopForNavBarThemes() {
        listOf(AppTheme.SIMPLE, AppTheme.KINETIC_SPHERE, AppTheme.CLEAN, AppTheme.FLUX_GLASS).forEach { theme ->
            assertTrue(DesktopNavPositionPolicy.showHeaderToggle(theme, isDesktop = true))
            assertFalse(DesktopNavPositionPolicy.showHeaderToggle(theme, isDesktop = false))
        }
        listOf(AppTheme.FREESTYLE, AppTheme.CAROUSEL).forEach { theme ->
            assertFalse(DesktopNavPositionPolicy.showHeaderToggle(theme, isDesktop = true))
            assertFalse(DesktopNavPositionPolicy.showHeaderToggle(theme, isDesktop = false))
        }
    }
}
