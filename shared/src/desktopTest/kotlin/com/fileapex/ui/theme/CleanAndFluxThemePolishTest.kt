package com.fileapex.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.supportedIconStyles
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesUiState
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.HomeTab
import com.fileapex.ui.adaptive.DockedDevicesActionBar
import com.fileapex.ui.adaptive.ModernDetailEmptyState
import com.fileapex.ui.adaptive.WideTopBarBrandMark
import com.fileapex.ui.adaptive.devicesListPaneContainerColor
import com.fileapex.ui.devices.DeviceCard
import com.fileapex.ui.devices.FileApexBottomBar
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanAndFluxThemePolishTest {

    @Test
    fun cleanChromeUsesNeutralLightSlateSurfaceWithoutRaisedTileBorders() {
        var chromeContainer = Color.Unspecified
        var chromeContent = Color.Unspecified
        var selectedBg = Color.Unspecified
        var selectedIcon = Color.Unspecified
        var selectedText = Color.Unspecified
        var headerActionTint = Color.Unspecified
        var tiledChrome = true

        runComposition(AppTheme.CLEAN, DesktopUiStyle.Standard) {
            chromeContainer = fileApexChromeContainerColor()
            chromeContent = fileApexChromeContentColor()
            selectedBg = fileApexNavSelectedBackgroundColor()
            selectedIcon = fileApexNavSelectedIconColor()
            selectedText = fileApexNavSelectedTextColor()
            headerActionTint = fileApexHeaderActionTint(onChromeBar = true)
            tiledChrome = isFileApexTiledChrome()
        }

        assertFalse("Clean should not draw JadedRaisedTile borders around header actions", tiledChrome)
        assertTrue("Clean chrome container should be a light neutral slate, not dark teal", chromeContainer.luminance() > 0.80f)
        assertTrue("Clean chrome content should be dark ink for high contrast", chromeContent.luminance() < 0.20f)
        assertTrue("Clean header icons should be dark neutral ink, not white", headerActionTint.luminance() < 0.35f)
        assertNotEquals("Clean active nav pill should use a tinted highlight instead of pure white", Color.White, selectedBg)
        assertEquals(FileApexTealDark, selectedIcon)
        assertEquals(FileApexTealDark, selectedText)
    }

    @Test
    fun simpleAndJadedSteelChromeTraitsRemainUnaffected() {
        var simpleTiled = true
        var jadedTiled = false
        var fluentCleanModern = false

        runComposition(AppTheme.SIMPLE, DesktopUiStyle.Standard) {
            simpleTiled = isFileApexTiledChrome()
        }
        runComposition(AppTheme.KINETIC_SPHERE, DesktopUiStyle.Standard, KineticStyle.JADED_STEEL) {
            jadedTiled = isFileApexTiledChrome()
        }
        runComposition(AppTheme.CLEAN, DesktopUiStyle.WindowsFluent) {
            fluentCleanModern = isFileApexTiledChrome()
        }

        assertTrue("Jaded Steel keeps tiled chrome", jadedTiled)
        assertFalse("Windows Fluent keeps standard non-tiled chrome", fluentCleanModern)
        assertTrue("Simple keeps its existing chrome path", simpleTiled)
    }

    @Test
    fun cleanBottomBarActiveHighlightDoesNotClipAgainstTopEdge() {
        val image = renderScene(width = 480, height = 96, density = 1f) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.CLEAN) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    FileApexBottomBar(
                        selected = HomeTab.Devices,
                        onMainHomeScreen = true,
                        onDevices = {},
                        onFiles = {},
                        onSettings = {}
                    )
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(image)
            try {
                // CleanModernBottomBar uses 8dp top padding above the pill tabs (y=2..7 at 1x density).
                // The upper padding region must remain the chrome surface color so active pills never clip at the top edge.
                val barTopRowColor = bitmap.getColor(80, 2)
                val barUpperPadColor = bitmap.getColor(80, 5)
                val barCenterColor = bitmap.getColor(80, 38)
                assertEquals(
                    "Top edge of Clean bottom bar must keep clean top margin above the active highlight",
                    barTopRowColor,
                    barUpperPadColor
                )
                assertNotEquals(
                    "Active Devices tab should still show a visible tinted highlight inside the bar",
                    barTopRowColor,
                    barCenterColor
                )
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    @Test
    fun fluxHeaderIconsUseCalmSilverInsteadOfNeonGreen() {
        var headerTint = Color.Unspecified
        runComposition(AppTheme.FLUX_GLASS, DesktopUiStyle.Standard) {
            headerTint = fileApexHeaderActionTint(onChromeBar = true)
        }
        assertNotEquals(
            "Flux WideTopBar action icons should not be neon matrix green",
            FluxGlassPalette.accent,
            headerTint
        )
        assertTrue("Flux header icons should be cool light silver", headerTint.luminance() > 0.60f)
    }

    @Test
    fun fluxSupportsBothIconStylesAndRendersDistinctStandardVsFluxIcons() {
        assertEquals(
            listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX),
            AppTheme.FLUX_GLASS.supportedIconStyles()
        )
        val profile = DeviceIconProfile(
            deviceId = "dev-1",
            deviceName = "Amanda",
            hardware = DeviceHardwareProfile(platform = "macOS", deviceMake = "Apple", deviceModel = "MacBookPro")
        )
        val standardImg = renderScene(width = 96, height = 96, density = 1f) {
            FileApexTheme(appTheme = AppTheme.FLUX_GLASS, themeIconStyle = ThemeIconStyle.STANDARD) {
                Box(Modifier.fillMaxSize().background(FluxGlassPalette.backgroundMid).padding(16.dp)) {
                    DeviceEntryIcon(profile = profile, modifier = Modifier.size(32.dp))
                }
            }
        }
        val fluxImg = renderScene(width = 96, height = 96, density = 1f) {
            FileApexTheme(appTheme = AppTheme.FLUX_GLASS, themeIconStyle = ThemeIconStyle.FLUX) {
                Box(Modifier.fillMaxSize().background(FluxGlassPalette.backgroundMid).padding(16.dp)) {
                    DeviceEntryIcon(profile = profile, modifier = Modifier.size(32.dp))
                }
            }
        }
        try {
            val stdPixels = Bitmap.makeFromImage(standardImg).use { it.readPixels()!! }
            val fluxPixels = Bitmap.makeFromImage(fluxImg).use { it.readPixels()!! }
            assertFalse("Flux Standard icons must differ visually from the Flux orb pack", stdPixels.contentEquals(fluxPixels))

            // Standard icon in Flux must be bright enough to read clearly on dark glass (not dark teal FileApexTealDark).
            val stdBitmap = Bitmap.makeFromImage(standardImg)
            try {
                var maxLum = 0f
                for (y in 16 until 48) {
                    for (x in 16 until 48) {
                        maxLum = maxOf(maxLum, Color(stdBitmap.getColor(x, y)).luminance())
                    }
                }
                assertTrue("Standard icon on Flux dark glass must have bright silver-mint contrast (maxLum=$maxLum)", maxLum > 0.45f)
            } finally {
                stdBitmap.close()
            }
        } finally {
            standardImg.close()
            fluxImg.close()
        }
    }

    @Test
    fun fluxDeviceCardHasNoDarkerInnerShadowRectangleArtifact() {
        val row = DeviceListRow(
            deviceId = "dev-1",
            deviceName = "Amanda",
            online = true,
            appVersion = "0.20.12",
            platform = "macOS"
        )
        val image = renderScene(width = 360, height = 110, density = 1f) {
            FileApexTheme(appTheme = AppTheme.FLUX_GLASS, themeIconStyle = ThemeIconStyle.STANDARD) {
                Box(Modifier.fillMaxSize().background(FluxGlassPalette.backgroundMid).padding(12.dp)) {
                    DeviceCard(
                        row = row,
                        onClick = {},
                        onRename = null,
                        onRemove = null
                    )
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(image)
            try {
                // Inside the card container above/below the text row, pixels should be uniform glass surface
                // with no darker Material elevation shadow box seam nested inside the card border.
                val innerTop = bitmap.getColor(180, 18)
                val innerMidRight = bitmap.getColor(320, 24)
                assertEquals(
                    "Flux card interior should be a single clean frosted glass layer without shadow seams",
                    innerTop,
                    innerMidRight
                )
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    @Test
    fun splitPaneListContainerMatchesCleanOffWhiteAndFluxTransparentCanvas() {
        assertEquals(
            CleanModernPalette.CanvasBackdrop,
            devicesListPaneContainerColor(AppTheme.CLEAN, isFluent = false)
        )
        assertEquals(
            Color.Transparent,
            devicesListPaneContainerColor(AppTheme.FLUX_GLASS, isFluent = false)
        )
        assertEquals(
            Color.Unspecified,
            devicesListPaneContainerColor(AppTheme.CLEAN, isFluent = true)
        )
    }

    @Test
    fun cleanDeviceCardUsesFlatWhiteSurfaceWithoutShadowHalo() {
        val row = DeviceListRow(
            deviceId = "dev-clean-1",
            deviceName = "Docker OMV",
            online = true,
            appVersion = "0.20.13",
            platform = "Linux"
        )
        val image = renderScene(width = 360, height = 110, density = 1f) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.CLEAN) {
                Box(Modifier.fillMaxSize().background(CleanModernPalette.CanvasBackdrop).padding(14.dp)) {
                    DeviceCard(
                        row = row,
                        onClick = {},
                        onRename = null,
                        onRemove = null
                    )
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(image)
            try {
                // Above the card border (y=4) is list pane off-white; inside right card interior is pure white.
                assertEquals(CleanModernPalette.CanvasBackdrop.toArgb(), bitmap.getColor(180, 4))
                assertEquals(CleanModernPalette.CardSurface.toArgb(), bitmap.getColor(320, 22))
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    @Test
    fun dockedFooterAndEmptyDetailStateRenderInCleanAndFlux() {
        listOf(AppTheme.CLEAN, AppTheme.FLUX_GLASS).forEach { theme ->
            val footer = renderScene(width = 360, height = 80, density = 1f) {
                FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = theme) {
                    DockedDevicesActionBar(
                        state = DevicesUiState(),
                        addMenuOpen = false,
                        onAddMenuOpenChange = {},
                        onSendClipboard = {},
                        onGenerateQr = {},
                        onJoinDevice = {}
                    )
                }
            }
            val emptyDetail = renderScene(width = 520, height = 360, density = 1f) {
                FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = theme) {
                    ModernDetailEmptyState(onOpenLocalFiles = {})
                }
            }
            footer.close()
            emptyDetail.close()
        }
    }

    @Test
    fun wideTopBarBrandMarkUsesFileApexAppIconInsteadOfGenericFolderSquare() {
        val image = renderScene(width = 40, height = 40, density = 1f, frames = 5) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.CLEAN) {
                Box(Modifier.fillMaxSize().background(CleanModernPalette.ChromeSurface).padding(7.dp)) {
                    WideTopBarBrandMark()
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(image)
            try {
                // Edge of the 26dp app icon at (9, 20) should be navy app-icon frame, not solid FileApexTealDark green.
                val edgePixel = bitmap.getColor(9, 20)
                val centerPixel = bitmap.getColor(20, 20)
                assertNotEquals(
                    "Brand mark should render the FileApex app icon rather than a flat FileApexTealDark square",
                    FileApexTealDark.toArgb(),
                    edgePixel
                )
                assertNotEquals(
                    "Brand mark should visibly draw the FileApex app icon over ChromeSurface",
                    CleanModernPalette.ChromeSurface.toArgb(),
                    centerPixel
                )
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    private fun runComposition(
        theme: AppTheme,
        style: DesktopUiStyle,
        kineticStyle: KineticStyle = KineticStyle.SPACE,
        block: @Composable () -> Unit
    ) {
        val scene = ImageComposeScene(width = 16, height = 16, density = Density(1f)) {
            FileApexTheme(uiStyle = style, appTheme = theme, kineticStyle = kineticStyle) {
                block()
            }
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
    }

    private fun renderScene(
        width: Int,
        height: Int,
        density: Float,
        frames: Int = 2,
        content: @Composable () -> Unit
    ): Image {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            content()
        }
        return try {
            repeat(frames - 1) { scene.render(0L) }
            scene.render(0L)
        } finally {
            scene.close()
        }
    }
}

private inline fun <T> Bitmap.use(block: (Bitmap) -> T): T = try {
    block(this)
} finally {
    close()
}
