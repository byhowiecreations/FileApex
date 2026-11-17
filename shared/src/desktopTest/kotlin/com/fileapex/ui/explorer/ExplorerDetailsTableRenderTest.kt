package com.fileapex.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.ExplorerListOrdering
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.AdaptiveExplorerView
import com.fileapex.ui.theme.FileApexTheme
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerDetailsTableRenderTest {
    private fun item(f: File) = RemoteFileItem(f.name, f.absolutePath, if (f.isDirectory) 0L else f.length(), f.lastModified(), f.isDirectory, "application/octet-stream")

    private val home: File = Files.createTempDirectory("explorer-table").toFile().also { root ->
        listOf("Applications", "Desktop", "Documents", "Downloads", "Movies", "Music").forEach { File(root, it).mkdirs() }
        File(root, "Downloads/Installers").mkdirs()
        File(root, "Downloads/Archive.dmg").writeBytes(ByteArray(2_000_000))
        File(root, "Downloads/notes.txt").writeBytes(ByteArray(1_500))
        File(root, "Downloads/photo.png").writeBytes(ByteArray(300_000))
        root.deleteOnExit()
    }

    private fun render(theme: AppTheme, sortMode: ExplorerSortMode?, png: String? = null): Bitmap {
        val dirs = (home.listFiles() ?: emptyArray()).filter { it.isDirectory }.map(::item)
        val inner = (File(home, "Downloads").listFiles() ?: emptyArray()).map(::item)
        val scene = ImageComposeScene(width = 1600, height = 900, density = Density(2f)) {
            FileApexTheme(appTheme = theme) {
                Box(Modifier.fillMaxSize().background(Color(0xFFF6F7F9))) {
                    AdaptiveExplorerView(
                        isWideDisplay = true,
                        viewMode = ExplorerViewMode.List,
                        panePath = home.absolutePath,
                        paneDirectories = ExplorerListOrdering.apply(dirs, "", ExplorerSortMode.Name),
                        contentDirectories = ExplorerListOrdering.apply(inner.filter { it.isDirectory }, "", ExplorerSortMode.Date),
                        contentFiles = ExplorerListOrdering.apply(inner.filter { !it.isDirectory }, "", ExplorerSortMode.Date),
                        selectedFolderPath = File(home, "Downloads").absolutePath,
                        canNavigateUp = false,
                        isSelectionMode = false,
                        selectedFileIds = emptySet(),
                        onNavigateUp = {}, onPaneFolderClick = {}, onContentDirectoryClick = {},
                        onFileOpen = {}, onFileLongPress = {},
                        sortMode = sortMode
                    )
                }
            }
        }
        scene.render(0L); scene.render(0L)
        val image = scene.render(0L)
        if (png != null) File(png).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        val bmp = Bitmap.makeFromImage(image)
        scene.close()
        return bmp
    }

    private fun differing(a: Bitmap, b: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (a.getColor(x, y) != b.getColor(x, y)) n++
        return n
    }

    @Test
    fun `wide Clean explorer draws the details table header and size and date cells`() {
        val withTable = render(AppTheme.CLEAN, ExplorerSortMode.Date, System.getenv("FILEAPEX_RENDER_PNG"))
        val without = render(AppTheme.CLEAN, null)
        assertTrue("table header and cells missing", differing(withTable, without, 800, 1600, 0, 900) > 500)
    }

    @Test
    fun `Freestyle keeps its own list without the table`() {
        val withTable = render(AppTheme.FREESTYLE, ExplorerSortMode.Date)
        val without = render(AppTheme.FREESTYLE, null)
        assertTrue("Freestyle must not change", differing(withTable, without, 0, 1600, 0, 900) == 0)
    }
}
