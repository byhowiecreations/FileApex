package com.fileapex.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.domain.demo.DemoModeState
import com.fileapex.i18n.AppI18n
import com.fileapex.ui.theme.FileApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeBannerLayoutTest {

    @Test
    fun `DemoModeBanner stacks Exit Demo button below banner text so text gets full width`() {
        var cardHeight = 0f
        var measured = false
        val scene = ImageComposeScene(width = 360, height = 240, density = Density(1f)) {
            FileApexTheme(appTheme = AppTheme.CLEAN, kineticStyle = KineticStyle.SPACE) {
                val layout = resolveDemoModeBannerLayoutForTest()
                assertEquals(DemoModeBannerButtonPlacement.BelowText, layout)
                DemoModeBanner(
                    onExitDemo = {},
                    modifier = androidx.compose.ui.Modifier.onGloballyPositioned {
                        cardHeight = it.boundsInRoot().height
                        measured = true
                    }
                )
            }
        }
        try {
            scene.render()
            assertTrue(measured)
            assertTrue("Expected stacked banner height > 80dp, got $cardHeight", cardHeight > 80f)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `ActiveDemoModeBanner renders Exit Demo in non-Simple themes including Kinetic and Freestyle`() {
        val original = DemoModeState.isDemoModeActive.value
        try {
            DemoModeState.launchDemo()
            assertTrue(DemoModeState.isDemoModeActive.value)
            val buttonLabel = AppI18n.t("exit_demo")
            assertFalse(buttonLabel.isBlank())

            listOf(AppTheme.KINETIC_SPHERE, AppTheme.FREESTYLE, AppTheme.CAROUSEL, AppTheme.CLEAN, AppTheme.FLUX_GLASS)
                .forEach { theme ->
                    var bounds: Rect? = null
                    val scene = ImageComposeScene(width = 400, height = 240, density = Density(1f)) {
                        FileApexTheme(appTheme = theme, kineticStyle = KineticStyle.SPACE) {
                            DevicesScreenTopBanners(
                                showDemoBanner = DemoModeState.isDemoModeActive.value,
                                onExitDemo = { DemoModeState.exitDemo() },
                                showClipboardBanner = false,
                                bannerModifier = androidx.compose.ui.Modifier.onGloballyPositioned {
                                    bounds = it.boundsInRoot()
                                }
                            )
                        }
                    }
                    try {
                        scene.render()
                        assertNotNull("$theme did not lay out top demo banner", bounds)
                        assertTrue("$theme demo banner collapsed to zero height", bounds!!.height > 0f)
                    } finally {
                        scene.close()
                    }
                }
        } finally {
            if (!original) DemoModeState.exitDemo()
        }
    }
}
