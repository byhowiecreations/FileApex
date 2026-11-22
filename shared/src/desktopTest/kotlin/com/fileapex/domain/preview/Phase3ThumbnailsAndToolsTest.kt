package com.fileapex.domain.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.platform.encodeLocalMediaThumbnailJpeg
import com.fileapex.presentation.BrowseTarget
import com.fileapex.ui.explorer.RemoteThumbnailCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.adaptive.simple.SimpleExplorerHeaderActions
import com.fileapex.ui.adaptive.simple.SimpleStorageToolsStrip
import com.fileapex.ui.adaptive.simple.SimpleStorageToolsStripMetrics
import com.fileapex.ui.theme.FileApexTheme
import com.fileapex.ui.tools.ToolsHeaderButton
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3ThumbnailsAndToolsTest {

    private fun item(name: String, mime: String = "application/octet-stream", isDir: Boolean = false) =
        RemoteFileItem(
            name = name,
            absolutePath = "/work/$name",
            sizeBytes = 128L,
            lastModified = 1000L,
            isDirectory = isDir,
            mimeType = mime
        )

    @Test
    fun `FilePreviewManager recognizes code and config text file extensions`() {
        val manager = FilePreviewManager(BrowseTarget.Local("local", "Local", "/work"))
        val textNames = listOf(
            "Main.kt", "build.gradle.kts", "App.java", "script.py", "index.js", "app.ts",
            "Widget.tsx", "View.jsx", "strings.xml", "config.yaml", "docker.yml", "Cargo.toml",
            "settings.gradle", "deploy.sh", "run.bat", "setup.ps1", "schema.sql", "styles.css",
            "index.html", "main.c", "engine.cpp", "engine.h", "lib.rs", "server.go", "rake.rb",
            "index.php", "ContentView.swift", "app.ini", "nginx.conf", ".env", "gradle.properties",
            ".gitignore", "README.md", "notes.txt", "data.json", "rows.csv", "app.log"
        )
        for (name in textNames) {
            assertTrue("Expected text preview support for $name", manager.isTextFile(item(name)))
        }
        for (nonText in listOf("photo.png", "clip.mp4", "archive.zip", "app.apk")) {
            assertFalse("Did not expect text preview for $nonText", manager.isTextFile(item(nonText)))
        }
    }

    @Test
    fun `RemoteThumbnailPolicy clamps edge bounds and detects media candidates`() {
        assertEquals(64, RemoteThumbnailPolicy.clampEdge(12))
        assertEquals(256, RemoteThumbnailPolicy.clampEdge(256))
        assertEquals(320, RemoteThumbnailPolicy.clampEdge(2000))

        assertTrue(RemoteThumbnailPolicy.isMediaCandidate(item("pic.jpg", "image/jpeg")))
        assertTrue(RemoteThumbnailPolicy.isMediaCandidate(item("pic.webp", "application/octet-stream")))
        assertTrue(RemoteThumbnailPolicy.isMediaCandidate(item("movie.mp4", "video/mp4")))
        assertFalse(RemoteThumbnailPolicy.isMediaCandidate(item("folder", isDir = true)))
        assertFalse(RemoteThumbnailPolicy.isMediaCandidate(item("notes.txt", "text/plain")))
    }

    @Test
    fun `encodeLocalMediaThumbnailJpeg creates bounded JPEG thumbnail from image and rejects missing files`() {
        val dir = createTempDirectory("thumb-test").toFile()
        try {
            val pngFile = File(dir, "sample.png")
            val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(20, 140, 210)
            graphics.fillRect(0, 0, 600, 400)
            graphics.dispose()
            ImageIO.write(image, "png", pngFile)

            val bytes = encodeLocalMediaThumbnailJpeg(pngFile.absolutePath, "image/png", maxEdge = 256)
            assertNotNull("Expected JPEG thumbnail bytes", bytes)
            assertTrue("Thumbnail should be non-empty", bytes!!.isNotEmpty())
            assertTrue("Thumbnail should stay within max bytes cap", bytes.size <= RemoteThumbnailPolicy.MAX_THUMBNAIL_BYTES)

            assertNull(encodeLocalMediaThumbnailJpeg(File(dir, "missing.png").absolutePath, "image/png", 256))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `RemoteThumbnailCache evicts oldest entry beyond maxEntries`() {
        val cache = RemoteThumbnailCache(maxEntries = 2)
        val a = androidx.compose.ui.graphics.ImageBitmap(4, 4)
        val b = androidx.compose.ui.graphics.ImageBitmap(4, 4)
        val c = androidx.compose.ui.graphics.ImageBitmap(4, 4)

        cache.put("k1", a)
        cache.put("k2", b)
        assertNotNull(cache.get("k1"))
        cache.put("k3", c)

        assertNull("k2 should be evicted as least-recently-used", cache.get("k2"))
        assertNotNull(cache.get("k1"))
        assertNotNull(cache.get("k3"))
    }

    @Test
    fun `SimpleStorageToolsStrip and ToolsHeaderButton render cleanly`() {
        val stripScene = ImageComposeScene(width = 600, height = 80, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleStorageToolsStrip(onClick = {})
            }
        }
        val headerBtnScene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.CLEAN) {
                ToolsHeaderButton(onClick = {}, tint = Color.Black)
            }
        }
        try {
            stripScene.render(0L).close()
            headerBtnScene.render(0L).close()
        } finally {
            stripScene.close()
            headerBtnScene.close()
        }
        assertTrue(
            "Storage tools strip should use two-line comfortable layout instead of single thin row",
            SimpleStorageToolsStripMetrics.minVerticalPaddingDp >= 12 && SimpleStorageToolsStripMetrics.maxSubtitleLines >= 2
        )
    }

    @Test
    fun `SimpleTopBar explorer actions render and invoke Storage Tools trigger when supplied`() {
        var opened = 0
        var toolsCenter = androidx.compose.ui.geometry.Offset.Zero
        val scene = ImageComposeScene(width = 420, height = 64, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleExplorerHeaderActions(
                    commands = ExplorerHeaderCommands(
                        onSelect = {},
                        onPaste = null,
                        onOpenTools = { opened++ }
                    ),
                    tint = Color.White,
                    toolsModifier = Modifier.onGloballyPositioned { coords ->
                        toolsCenter = coords.boundsInRoot().center
                    }
                )
            }
        }
        try {
            scene.render(0L).close()
            assertTrue("Storage Tools pill should be laid out", toolsCenter.x > 0f && toolsCenter.y > 0f)
            scene.sendPointerEvent(
                eventType = androidx.compose.ui.input.pointer.PointerEventType.Press,
                position = toolsCenter,
                type = androidx.compose.ui.input.pointer.PointerType.Mouse,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true),
                timeMillis = 10L
            )
            scene.sendPointerEvent(
                eventType = androidx.compose.ui.input.pointer.PointerEventType.Release,
                position = toolsCenter,
                type = androidx.compose.ui.input.pointer.PointerType.Mouse,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = false),
                timeMillis = 30L
            )
            scene.render(40_000_000L).close()
            assertEquals("Expected Storage Tools pill click to invoke onOpenTools", 1, opened)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `file picker never enables Storage Tools and ungranted click never arms open-tools signal`() {
        assertFalse(
            com.fileapex.ui.explorer.ExplorerToolsPolicy.isToolsAvailable(
                isLocal = true,
                isPicker = true,
                supported = true
            )
        )
        assertTrue(
            com.fileapex.ui.explorer.ExplorerToolsPolicy.isToolsAvailable(
                isLocal = true,
                isPicker = false,
                supported = true
            )
        )

        com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.consume(
            com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.requests.value
        )
        com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.requestOpenIfPermitted(
            hasStoragePermission = false
        )
        assertEquals(0, com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.requests.value)

        com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.requestOpenIfPermitted(
            hasStoragePermission = true
        )
        val armed = com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.requests.value
        assertTrue(armed > 0)
        com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.consume(armed)
        assertEquals(0, com.fileapex.ui.adaptive.simple.LocalExplorerOpenToolsSignal.requests.value)
    }

    @Test
    fun `pdf and audio files route to native external open policy`() {
        val manager = FilePreviewManager(BrowseTarget.Local("local", "Local", "/work"))
        assertTrue(manager.isExternalMediaFile(item("manual.pdf", "application/pdf")))
        assertTrue(manager.isExternalMediaFile(item("invoice.PDF", "application/octet-stream")))
        assertTrue(manager.isExternalMediaFile(item("track.mp3", "audio/mpeg")))
        assertTrue(manager.isExternalMediaFile(item("voice.m4a", "application/octet-stream")))
        assertTrue(manager.isExternalMediaFile(item("sample.flac", "audio/flac")))
        assertFalse(manager.isExternalMediaFile(item("folder.pdf", isDir = true)))
        assertFalse(manager.isExternalMediaFile(item("photo.jpg", "image/jpeg")))
        assertFalse(manager.isExternalMediaFile(item("readme.txt", "text/plain")))
        assertFalse(manager.isExternalMediaFile(item("archive.zip", "application/zip")))

        assertEquals(ExplorerOpenKind.Pdf, ExplorerFileOpenPolicy.classify(manager, item("manual.pdf", "application/pdf")))
        assertEquals(ExplorerOpenKind.Pdf, ExplorerFileOpenPolicy.classify(manager, item("invoice.PDF", "application/octet-stream")))
        assertEquals(ExplorerOpenKind.ExternalMedia, ExplorerFileOpenPolicy.classify(manager, item("track.mp3", "audio/mpeg")))
        assertEquals(ExplorerOpenKind.Image, ExplorerFileOpenPolicy.classify(manager, item("photo.png", "image/png")))
        assertEquals(ExplorerOpenKind.Text, ExplorerFileOpenPolicy.classify(manager, item("notes.md", "text/markdown")))
        assertEquals(ExplorerOpenKind.Directory, ExplorerFileOpenPolicy.classify(manager, item("docs", isDir = true)))
        assertEquals(ExplorerOpenKind.Fallback, ExplorerFileOpenPolicy.classify(manager, item("archive.zip", "application/zip")))
    }

    @Test
    fun `preview fits only for known sizes within the cap`() {
        val manager = FilePreviewManager(BrowseTarget.Local("local", "Local", "/work"))
        val cap = FilePreviewManager.MAX_PREVIEW_BYTES
        assertTrue(manager.previewFits(RemoteFileItem("a.png", "/a.png", 1L, 0L, false, "image/png"), cap))
        assertTrue(manager.previewFits(RemoteFileItem("a.png", "/a.png", cap, 0L, false, "image/png"), cap))
        assertFalse(manager.previewFits(RemoteFileItem("a.png", "/a.png", cap + 1, 0L, false, "image/png"), cap))
        assertFalse(manager.previewFits(RemoteFileItem("a.png", "/a.png", 0L, 0L, false, "image/png"), cap))
    }
}
