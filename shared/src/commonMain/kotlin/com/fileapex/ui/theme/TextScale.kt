package com.fileapex.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.fileapex.di.FileApexServices

/** The same density with the font scale multiplied by [percent]; 100 returns [base] unchanged. */
internal fun scaledForText(base: Density, percent: Int): Density =
    if (percent == 100) base else Density(base.density, base.fontScale * percent / 100f)

/** Applies the user's text size setting to everything below. */
@Composable
fun ProvideTextScale(content: @Composable () -> Unit) {
    val percent by FileApexServices.settings.textScale.percent.collectAsState()
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides scaledForText(base, percent), content = content)
}
