package com.fileapex.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.backgroundBrush
import com.fileapex.data.settings.cardBorder
import com.fileapex.data.settings.cardContainerColor
import com.fileapex.data.settings.defaultIconStyle
import com.fileapex.data.settings.supportedIconStyles
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pixel-exact and value baselines for the existing themes. Goldens are recorded once from unchanged code with
 * `-PrecordThemeGoldens=true` and must never be re-recorded together with a theme refactor.
 */
class ThemeBaselineTest {

    private val goldenDir = File(requireNotNull(System.getProperty("fileapex.themeGoldenDir")))
    private val diffDir = File(requireNotNull(System.getProperty("fileapex.themeDiffDir")))
    private val recording = System.getProperty("fileapex.recordThemeGoldens") == "true"

    private val combos: List<Pair<AppTheme, DesktopUiStyle>> =
        AppTheme.entries.flatMap { theme -> DesktopUiStyle.entries.map { theme to it } }

    @Test
    fun themeSurfacesMatchGoldenPixels() {
        val mismatches = combos.mapNotNull { (theme, style) ->
            val name = "${theme.name}-${style.name}.png"
            val actual = render(theme, style)
            try {
                checkPixels(name, actual)
            } finally {
                actual.close()
            }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun iconPacksMatchGoldenPixels() {
        val mismatches = AppTheme.entries.flatMap { theme ->
            theme.supportedIconStyles().mapNotNull { iconStyle ->
                val name = "ICONS-${theme.name}-${iconStyle.name}.png"
                val actual = renderIcons(theme, iconStyle)
                try {
                    checkPixels(name, actual)
                } finally {
                    actual.close()
                }
            }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun themeValuesMatchGolden() {
        val lines = combos.flatMap { (theme, style) -> captureValues(theme, style) }
        checkText("theme-values.txt", lines.joinToString("\n", postfix = "\n"))
    }

    @Test
    fun renderingIsDeterministic() {
        val first = render(AppTheme.FLUX_GLASS, DesktopUiStyle.Standard)
        val second = render(AppTheme.FLUX_GLASS, DesktopUiStyle.Standard)
        try {
            assertTrue(pixels(first).contentEquals(pixels(second)))
        } finally {
            first.close()
            second.close()
        }
    }

    private fun render(theme: AppTheme, style: DesktopUiStyle): Image {
        val scene = ImageComposeScene(
            width = WIDTH_PX,
            height = HEIGHT_PX,
            density = Density(density = DENSITY, fontScale = 1f)
        ) {
            FileApexTheme(uiStyle = style, appTheme = theme, themeIconStyle = theme.defaultIconStyle()) {
                ThemeSample(theme)
            }
        }
        return try {
            scene.render(FRAME_TIME_NANOS)
            scene.render(FRAME_TIME_NANOS)
        } finally {
            scene.close()
        }
    }

    private fun renderIcons(theme: AppTheme, iconStyle: ThemeIconStyle): Image {
        val scene = ImageComposeScene(
            width = ICON_WIDTH_PX,
            height = ICON_HEIGHT_PX,
            density = Density(density = DENSITY, fontScale = 1f)
        ) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = theme, themeIconStyle = iconStyle) {
                IconSample(theme)
            }
        }
        return try {
            repeat(ICON_SETTLE_FRAMES) { scene.render(FRAME_TIME_NANOS) }
            scene.render(FRAME_TIME_NANOS)
        } finally {
            scene.close()
        }
    }

    private fun captureValues(theme: AppTheme, style: DesktopUiStyle): List<String> {
        val lines = mutableListOf<String>()
        val scene = ImageComposeScene(width = 4, height = 4, density = Density(DENSITY, 1f)) {
            FileApexTheme(uiStyle = style, appTheme = theme, themeIconStyle = theme.defaultIconStyle()) {
                val captured = valueLines(theme, style)
                lines.clear()
                lines.addAll(captured)
            }
        }
        try {
            scene.render(FRAME_TIME_NANOS)
        } finally {
            scene.close()
        }
        return lines
    }

    private fun checkPixels(name: String, actual: Image): String? {
        val golden = File(goldenDir, name)
        if (recording) {
            goldenDir.mkdirs()
            golden.writeBytes(encodePng(actual))
            return null
        }
        if (!golden.exists()) return "$name: golden missing (record from unchanged code first)"
        val expected = Image.makeFromEncoded(golden.readBytes())
        return try {
            if (expected.width != actual.width || expected.height != actual.height) {
                "$name: size ${actual.width}x${actual.height} != golden ${expected.width}x${expected.height}"
            } else {
                val diff = countDifferentPixels(pixels(expected), pixels(actual))
                if (diff == 0) {
                    null
                } else {
                    diffDir.mkdirs()
                    File(diffDir, name).writeBytes(encodePng(actual))
                    "$name: $diff pixels differ (actual written to ${diffDir.path})"
                }
            }
        } finally {
            expected.close()
        }
    }

    private fun checkText(name: String, actual: String) {
        val golden = File(goldenDir, name)
        if (recording) {
            goldenDir.mkdirs()
            golden.writeText(actual)
            return
        }
        if (!golden.exists()) fail("$name: golden missing (record from unchanged code first)")
        assertEquals(golden.readText(), actual)
    }

    private fun pixels(image: Image): ByteArray {
        val bitmap = Bitmap.makeFromImage(image)
        return try {
            requireNotNull(bitmap.readPixels())
        } finally {
            bitmap.close()
        }
    }

    private fun countDifferentPixels(expected: ByteArray, actual: ByteArray): Int {
        var diff = 0
        var i = 0
        while (i < expected.size) {
            if (expected[i] != actual[i] || expected[i + 1] != actual[i + 1] ||
                expected[i + 2] != actual[i + 2] || expected[i + 3] != actual[i + 3]
            ) {
                diff++
            }
            i += 4
        }
        return diff
    }

    private fun encodePng(image: Image): ByteArray =
        requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes

    private companion object {
        const val WIDTH_PX = 900
        const val HEIGHT_PX = 1100
        const val DENSITY = 2f
        const val FRAME_TIME_NANOS = 0L
        const val ICON_WIDTH_PX = 1000
        const val ICON_HEIGHT_PX = 420
        const val ICON_SETTLE_FRAMES = 5
    }
}

@Composable
private fun IconSample(theme: AppTheme) {
    val background = theme.backgroundBrush()
    val rootModifier = if (background != null) {
        Modifier.fillMaxSize().background(background)
    } else {
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    }
    Column(modifier = rootModifier.padding(12.dp)) {
        SampleIconProfiles.chunked(5).forEach { row ->
            Row {
                row.forEach { profile ->
                    DeviceEntryIcon(profile = profile, modifier = Modifier.padding(8.dp).size(80.dp))
                }
            }
        }
    }
}

private val SampleIconProfiles = listOf(
    sampleProfile("Mac", "macOS", "Apple", "MacBookPro"),
    sampleProfile("Nimo", "Windows", "", ""),
    sampleProfile("Google Pixel 11 Pro", "Android", "Google", "Pixel 11 Pro"),
    sampleProfile("Samsung Fold8", "Android", "samsung", "SM-F971U"),
    sampleProfile("Moto razr fold 2026", "Android", "motorola", "razr fold 2026"),
    sampleProfile("Moto Signature", "Android", "motorola", "moto signature"),
    sampleProfile("HONOR Magic8 Pro", "Android", "HONOR", "Magic8 Pro"),
    sampleProfile("HONOR Magic v5", "Android", "HONOR", "Magic V5"),
    sampleProfile("HONOR MTN-NX1", "Android", "HONOR", "MTN-NX1"),
    sampleProfile("CMF", "Android", "CMF", "Phone 2 Pro")
)

private fun sampleProfile(name: String, platform: String, make: String, model: String) = DeviceIconProfile(
    deviceId = name,
    deviceName = name,
    hardware = DeviceHardwareProfile(platform = platform, deviceMake = make, deviceModel = model)
)

private fun Color.hex(): String = "#%08X".format(toArgb())

private fun TextStyle.describe(): String = "size=$fontSize weight=$fontWeight line=$lineHeight"

private fun BorderStroke.describe(): String = "width=$width brush=$brush"

@Composable
private fun valueLines(theme: AppTheme, style: DesktopUiStyle): List<String> {
    val prefix = "${theme.name}/${style.name}"
    val typography = MaterialTheme.typography
    return listOf(
        "$prefix colorScheme=${MaterialTheme.colorScheme}",
        "$prefix shapes=${MaterialTheme.shapes}",
        "$prefix type.titleLarge=${typography.titleLarge.describe()}",
        "$prefix type.titleMedium=${typography.titleMedium.describe()}",
        "$prefix type.bodyLarge=${typography.bodyLarge.describe()}",
        "$prefix type.bodyMedium=${typography.bodyMedium.describe()}",
        "$prefix type.labelLarge=${typography.labelLarge.describe()}",
        "$prefix backgroundBrush=${theme.backgroundBrush()}",
        "$prefix cardContainerColor=${theme.cardContainerColor().hex()}",
        "$prefix cardBorder=${theme.cardBorder(BorderStroke(1.dp, Color.Gray)).describe()}",
        "$prefix iconStyles=${theme.supportedIconStyles()} default=${theme.defaultIconStyle()}",
        "$prefix isFluentUi=${isFileApexFluentUi()}",
        "$prefix isFluxGlass=${isFileApexFluxGlass()}",
        "$prefix isKineticSphere=${isFileApexKineticSphere()}",
        "$prefix isFreestyle=${isFileApexFreestyle()}",
        "$prefix isCustomGlass=${isFileApexCustomGlassTheme()}",
        "$prefix chromeContainer=${fileApexChromeContainerColor().hex()}",
        "$prefix chromeContent=${fileApexChromeContentColor().hex()}",
        "$prefix headerActionTint=${fileApexHeaderActionTint().hex()}",
        "$prefix navSelectedBackground=${fileApexNavSelectedBackgroundColor().hex()}",
        "$prefix navSelectedIcon=${fileApexNavSelectedIconColor().hex()}",
        "$prefix navUnselectedIcon=${fileApexNavUnselectedIconColor().hex()}",
        "$prefix navSelectedText=${fileApexNavSelectedTextColor().hex()}",
        "$prefix navUnselectedText=${fileApexNavUnselectedTextColor().hex()}"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSample(theme: AppTheme) {
    val background = theme.backgroundBrush()
    val rootModifier = if (background != null) {
        Modifier.fillMaxSize().background(background)
    } else {
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    }
    Box(modifier = rootModifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("FileApex") },
                colors = fileApexTopAppBarColors(),
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.ViewModule, contentDescription = null, tint = fileApexHeaderActionTint())
                    }
                    NoteHeaderButton(onOpenNotes = {}, viewMode = ExplorerViewMode.List)
                },
                modifier = Modifier.fileApexChromeBottomEdge()
            )
            FileApexPaneSectionHeader(title = "Devices", onBack = {})
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(containerColor = fileApexChromeContainerColor()) {
                    SampleDestinations.forEachIndexed { index, (label, icon) ->
                        NavigationRailItem(
                            selected = index == 0,
                            onClick = {},
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                            colors = fileApexNavigationRailItemColors()
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                    val shape = RoundedCornerShape(16.dp)
                    val border = theme.cardBorder(BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(theme.cardContainerColor(MaterialTheme.colorScheme.surface), shape)
                            .border(border, shape)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text("Card title", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Body text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SchemeSwatches()
                }
            }
            NavigationBar(
                containerColor = fileApexChromeContainerColor(),
                modifier = Modifier.fileApexChromeTopEdge()
            ) {
                SampleDestinations.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = index == 0,
                        onClick = {},
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = fileApexNavigationBarItemColors()
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemeSwatches() {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
        scheme.secondary, scheme.onSecondary, scheme.secondaryContainer, scheme.onSecondaryContainer,
        scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface,
        scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline, scheme.outlineVariant,
        scheme.error, scheme.onError
    )
    Column(modifier = Modifier.padding(top = 16.dp)) {
        colors.chunked(6).forEach { row ->
            Row {
                row.forEach { color ->
                    Box(modifier = Modifier.padding(2.dp).size(32.dp).background(color))
                }
            }
        }
    }
}

private val SampleDestinations = listOf(
    "Devices" to Icons.Default.Devices,
    "Files" to Icons.Default.Folder,
    "Settings" to Icons.Default.Settings
)
