package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.ui.theme.FileApexTheme
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStateTest {
    private fun measure(theme: AppTheme, kinetic: KineticStyle, narrow: Boolean = false, action: Boolean): Pair<Float, Float> {
        var width = 0f
        var height = 0f
        val scene = ImageComposeScene(width = if (narrow) 220 else 480, height = 640, density = Density(1f)) {
            FileApexTheme(appTheme = theme, kineticStyle = kinetic) {
                EmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = "This folder is empty",
                    hint = "Copy, move or drop files here to add them to this folder.",
                    actionLabel = if (action) "Open local files" else null,
                    onAction = if (action) ({}) else null,
                    modifier = Modifier.onGloballyPositioned {
                        width = it.boundsInRoot().width
                        height = it.boundsInRoot().height
                    }
                )
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        return width to height
    }

    @Test
    fun itLaysOutInEveryThemeAndStyle() {
        for (theme in AppTheme.entries) for (style in KineticStyle.entries) {
            val (w, h) = measure(theme, style, action = false)
            assertTrue("$theme/$style collapsed: ${w}x$h", w > 0f && h > 0f)
        }
    }

    @Test
    fun anActionAddsHeightAndAMissingOneAddsNone() {
        for (theme in AppTheme.entries) {
            val (_, plain) = measure(theme, KineticStyle.SPACE, action = false)
            val (_, withAction) = measure(theme, KineticStyle.SPACE, action = true)
            assertTrue("$theme: action did not render ($plain vs $withAction)", withAction > plain)
        }
    }

    @Test
    fun itFitsANarrowPane() {
        for (theme in AppTheme.entries) {
            val (w, _) = measure(theme, KineticStyle.SPACE, narrow = true, action = true)
            assertTrue("$theme overflowed a 220 px pane: $w", w <= 220f)
        }
    }

    @Test
    fun aMissingHintOrActionIsNotRequired() {
        var height = 0f
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            FileApexTheme(appTheme = AppTheme.CLEAN, kineticStyle = KineticStyle.SPACE) {
                EmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = "Nothing here",
                    modifier = Modifier.onGloballyPositioned { height = it.boundsInRoot().height }
                )
            }
        }
        try {
            scene.render()
            assertTrue("title only collapsed", height > 0f)
        } finally {
            scene.close()
        }
    }
}
