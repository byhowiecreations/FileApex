package com.fileapex.ui.theme

import androidx.compose.ui.graphics.Color
import com.fileapex.data.settings.AccentSettings
import com.fileapex.data.settings.AppTheme
import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The accent a user can pick for the light themes: Clean defaults to blue, every choice stays readable, and the pick is stored. */
class AccentTest {
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `clean defaults to blue and every other theme keeps teal`() {
        assertEquals(AccentChoice.Blue, AccentChoice.defaultFor(AppTheme.CLEAN))
        AppTheme.entries.filter { it != AppTheme.CLEAN }.forEach {
            assertEquals(it.name, AccentChoice.Teal, AccentChoice.defaultFor(it))
        }
    }

    @Test
    fun `white text on every accent and the accent on its own tint are readable`() {
        for (choice in AccentChoice.entries) {
            val colors = choice.colors
            assertTrue("${choice.name} main vs white", contrast(colors.main, Color.White) >= 4.5)
            assertTrue("${choice.name} dark vs white", contrast(colors.dark, Color.White) >= 4.5)
            assertTrue("${choice.name} dark on tint", contrast(colors.dark, colors.container) >= 4.5)
        }
    }

    @Test
    fun `the choices are distinct`() {
        assertEquals(AccentChoice.entries.size, AccentChoice.entries.map { it.colors.main }.toSet().size)
    }

    @Test
    fun `no pick means no stored choice, and a pick is read back`() {
        val store = MapKvStore()
        val settings = AccentSettings(store)
        assertNull(settings.choice.value)
        settings.setChoice(AccentChoice.Violet)
        assertEquals(AccentChoice.Violet, settings.choice.value)
        assertEquals(AccentChoice.Violet, AccentSettings(store).choice.value)
        settings.setChoice(null)
        assertNull(AccentSettings(store).choice.value)
    }

    @Test
    fun `an unknown stored value is ignored`() {
        val store = MapKvStore()
        store.putString("accent_choice", "chartreuse")
        assertNull(AccentSettings(store).choice.value)
    }
}
