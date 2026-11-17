package com.fileapex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.fileapex.di.FileApexServices
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.explorer.ExplorerDetails
import com.fileapex.ui.explorer.ExplorerPaneFit

/** The folder pane's frame in the wide explorer: inset glass for Jaded Steel, an inset card for Simple and Carousel, flat otherwise. */
@Composable
internal fun RowScope.explorerLeftPaneModifier(ink: ExplorerInk, fraction: Float, bottomPadding: Dp): Modifier {
    return if (ink.jadedGlyphs) {
        val haze = LocalJadedHazeState.current
        val paneShape = RoundedCornerShape(16.dp)
        Modifier
            .weight(fraction)
            .fillMaxHeight()
            .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = bottomPadding)
            .clip(paneShape)
            .then(
                if (haze != null) {
                    Modifier.hazeEffect(
                        state = haze,
                        style = HazeStyle(
                            backgroundColor = Color.Transparent,
                            tints = listOf(HazeTint(Color.White.copy(alpha = 0.10f))),
                            blurRadius = 24.dp,
                            noiseFactor = 0.04f
                        )
                    )
                } else {
                    Modifier
                }
            )
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.16f),
                    1f to Color.White.copy(alpha = 0.05f)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.22f),
                    1f to Color.White.copy(alpha = 0.10f)
                ),
                shape = paneShape
            )
    } else if (LocalAppTheme.current.let { it == AppTheme.SIMPLE || it == AppTheme.CAROUSEL }) {
        // Same inset, rounded pane as Jaded Steel; the colors stay Simple's own.
        val paneShape = RoundedCornerShape(20.dp)
        Modifier
            .weight(fraction)
            .fillMaxHeight()
            .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = bottomPadding)
            .clip(paneShape)
            .background(ink.paneBackground)
            .border(1.dp, ink.divider, paneShape)
    } else {
        Modifier
            .weight(fraction)
            .fillMaxHeight()
            .background(ink.paneBackground)
    }
}

private const val PANE_ROW_CHROME_DP = 76f
private const val MODIFIED_COLUMN_MIN_PANE_DP = 510f

internal class ExplorerSplit(
    val fraction: Float,
    val details: ExplorerDetails?,
    private val manual: MutableState<Boolean>
) {
    /** The first drag of the divider switches auto-fit off for this folder and starts from the width now showing. */
    fun takeOverFromAutoFit() {
        if (manual.value) return
        manual.value = true
        FileApexServices.settings.setExplorerSplitFraction(fraction, persist = false)
    }
}

/** Left pane width (auto-fitted to the longest folder name until the user drags it; Freestyle keeps the saved one) and the details table setup. */
@Composable
internal fun rememberExplorerSplit(
    panePath: String,
    paneDirectories: List<RemoteFileItem>,
    totalWidthPx: Float,
    viewMode: ExplorerViewMode,
    sortMode: ExplorerSortMode?,
    onSortModeChange: (ExplorerSortMode) -> Unit
): ExplorerSplit {
    val savedFraction by FileApexServices.settings.explorerSplitFraction.collectAsState()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.bodyLarge
    val fitFraction = remember(paneDirectories, totalWidthPx) {
        val longestPx = paneDirectories.take(200).maxOfOrNull { measurer.measure(it.name, nameStyle).size.width } ?: 0
        ExplorerPaneFit.fraction(
            longestNameDp = longestPx / density.density,
            chromeDp = PANE_ROW_CHROME_DP,
            totalWidthDp = totalWidthPx / density.density
        )
    }
    val manual = remember(panePath) { mutableStateOf(false) }
    val freestyle = LocalAppTheme.current == AppTheme.FREESTYLE
    val fraction = if (!freestyle && !manual.value) fitFraction else savedFraction
    val details = if (!freestyle && viewMode == ExplorerViewMode.List && sortMode != null) {
        val contentWidthDp = totalWidthPx * (1f - fraction) / density.density
        ExplorerDetails(sortMode, onSortModeChange, showModified = contentWidthDp >= MODIFIED_COLUMN_MIN_PANE_DP)
    } else null
    return ExplorerSplit(fraction, details, manual)
}
