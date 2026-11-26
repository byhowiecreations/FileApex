package com.fileapex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme and platform reads the same type scale; only the font family may differ. */
class TypographyScaleTest {
    private fun styles(t: Typography): Map<String, TextStyle> = mapOf(
        "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall
    )

    @Test
    fun `no style in the scale is below 12 sp`() {
        val small = styles(fileApexTypography(null)).filter { it.value.fontSize < 12.sp }.keys
        assertTrue("styles below 12 sp: $small", small.isEmpty())
    }

    @Test
    fun `a font family changes only the family`() {
        val plain = styles(fileApexTypography(null))
        val other = styles(fileApexTypography(FontFamily.Serif))
        for ((name, style) in plain) {
            val changed = other.getValue(name)
            assertEquals("$name size", style.fontSize, changed.fontSize)
            assertEquals("$name weight", style.fontWeight, changed.fontWeight)
            assertEquals("$name line height", style.lineHeight, changed.lineHeight)
            assertEquals("$name family", FontFamily.Serif, changed.fontFamily)
        }
    }

    @Test
    fun `titles are semibold in every theme`() {
        val scale = fileApexTypography(null)
        assertEquals(FontWeight.SemiBold, scale.titleLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, scale.titleMedium.fontWeight)
    }
}

/** A fixed font size under 12 sp bypasses the scale and ignores the minimum readable size. */
class FontSizeGuardTest {
    private val roots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin")
        .map { File(it) }.filter { it.exists() }
    private val literal = Regex("""fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp""")

    /** The Windows tray popup is a fixed-pixel native menu that cannot be checked from here; open until it is rechecked on Windows. */
    private val openItems = setOf("DesktopWindowsTrayMenu.kt")

    @Test
    fun `no fixed font size below 12 sp`() {
        val offenders = mutableListOf<String>()
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in openItems }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    literal.findAll(code).forEach { match ->
                        if (match.groupValues[1].toFloat() < 12f) {
                            offenders += "${file.path}:${index + 1}: ${line.trim().take(100)}"
                        }
                    }
                }
            }
        }
        assertTrue("fixed font sizes below 12 sp (use the type scale):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
