package com.fileapex.ui.theme

import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.TextScaleSettings
import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's text size is a percentage on top of the system font size, kept to a range where layouts still hold. */
class TextScaleTest {
    @Test
    fun `defaults to 100 percent`() {
        assertEquals(100, TextScaleSettings(MapKvStore()).percent.value)
    }

    @Test
    fun `values are kept inside the range and on the step`() {
        val settings = TextScaleSettings(MapKvStore())
        settings.setPercent(40)
        assertEquals(80, settings.percent.value)
        settings.setPercent(400)
        assertEquals(130, settings.percent.value)
        settings.setPercent(103)
        assertEquals(105, settings.percent.value)
        settings.setPercent(92)
        assertEquals(90, settings.percent.value)
    }

    @Test
    fun `the choice is stored and read back`() {
        val store = MapKvStore()
        TextScaleSettings(store).setPercent(115)
        assertEquals(115, TextScaleSettings(store).percent.value)
    }

    @Test
    fun `a stored value that is not a number falls back to 100`() {
        val store = MapKvStore()
        store.putString("text_scale_percent", "big")
        assertEquals(100, TextScaleSettings(store).percent.value)
    }

    @Test
    fun `the percentage multiplies the font scale and leaves the density alone`() {
        val scaled = scaledForText(Density(density = 2.5f, fontScale = 1.2f), percent = 80)
        assertEquals(2.5f, scaled.density, 0.0001f)
        assertEquals(0.96f, scaled.fontScale, 0.0001f)
    }

    @Test
    fun `100 percent changes nothing`() {
        val base = Density(density = 3f, fontScale = 1.1f)
        assertEquals(base, scaledForText(base, percent = 100))
    }
}
