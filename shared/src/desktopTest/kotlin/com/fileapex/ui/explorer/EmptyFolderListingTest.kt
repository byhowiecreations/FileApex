package com.fileapex.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.AdaptiveExplorerView
import com.fileapex.ui.theme.FileApexTheme
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyFolderListingTest {
    private fun litPixelsBelowTheParentRow(canNavigateUp: Boolean): Int {
        val background = Color(0xFF101010)
        val scene = ImageComposeScene(width = 420, height = 700, density = Density(1f)) {
            FileApexTheme(appTheme = AppTheme.CLEAN) {
                Box(Modifier.fillMaxSize().background(background)) {
                    AdaptiveExplorerView(
                        isWideDisplay = false,
                        viewMode = ExplorerViewMode.List,
                        panePath = "/storage/emulated/0/Download/empty",
                        paneDirectories = emptyList(),
                        contentDirectories = emptyList(),
                        contentFiles = emptyList(),
                        selectedFolderPath = null,
                        canNavigateUp = canNavigateUp,
                        isSelectionMode = false,
                        selectedFileIds = emptySet(),
                        onNavigateUp = {},
                        onPaneFolderClick = {},
                        onContentDirectoryClick = {},
                        onFileOpen = {},
                        onFileLongPress = {}
                    )
                }
            }
        }
        val image = scene.render(0L).also { scene.render(0L) }
        return try {
            val bitmap = Bitmap.makeFromImage(scene.render(0L))
            try {
                val base = bitmap.getColor(410, 690)
                var lit = 0
                for (y in 100 until 600) for (x in 40 until 380) if (bitmap.getColor(x, y) != base) lit++
                lit
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
            scene.close()
        }
    }

    @Test
    fun anEmptySubfolderShowsTheEmptyStateBelowTheParentRow() {
        assertTrue("empty subfolder drew nothing", litPixelsBelowTheParentRow(canNavigateUp = true) > 200)
    }

    @Test
    fun anEmptyRootShowsTheEmptyState() {
        assertTrue("empty root drew nothing", litPixelsBelowTheParentRow(canNavigateUp = false) > 200)
    }
}
