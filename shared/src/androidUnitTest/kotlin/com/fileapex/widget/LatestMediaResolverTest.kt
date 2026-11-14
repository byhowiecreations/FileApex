package com.fileapex.widget

import com.fileapex.domain.widget.WidgetActionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatestMediaResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun returnsPermissionRequiredWhenStorageAccessIsMissing() {
        val resolver = LatestMediaResolver(
            hasFullAccess = { false },
            storageRootsProvider = { listOf(tempFolder.root) }
        )
        val result = resolver.resolve(WidgetActionTarget.SEND_LAST_PHOTO)
        assertTrue(result is LatestMediaResult.PermissionRequired)
    }

    @Test
    fun resolvesNewestPhotoScreenshotVideoAndDownloadWhileSkippingTempFiles() {
        val root = tempFolder.root
        val camera = File(root, "DCIM/Camera").apply { mkdirs() }
        val picShots = File(root, "Pictures/Screenshots").apply { mkdirs() }
        val dcimShots = File(root, "DCIM/Screenshots").apply { mkdirs() }
        val downloads = File(root, "Download").apply { mkdirs() }
        val movies = File(root, "Movies").apply { mkdirs() }

        createTimedFile(camera, "IMG_001.jpg", 1_000L)
        val newestPhoto = createTimedFile(camera, "IMG_002.heic", 3_000L)
        createTimedFile(camera, ".pending-IMG_003.jpg", 5_000L)
        createTimedFile(camera, ".trashed-IMG_004.jpg", 5_000L)
        createTimedFile(camera, "clip.mp4", 4_000L)

        createTimedFile(picShots, "shot1.png", 2_000L)
        val newestShot = createTimedFile(dcimShots, "shot2.png", 4_500L)

        val newestVideo = createTimedFile(movies, "VID_002.mp4", 6_000L)
        createTimedFile(downloads, "old.pdf", 1_500L)
        val newestDownload = createTimedFile(downloads, "invoice.pdf", 7_000L)
        createTimedFile(downloads, "partial.fileapex-part", 9_000L)

        val resolver = LatestMediaResolver(
            hasFullAccess = { true },
            storageRootsProvider = { listOf(root) }
        )

        assertEquals(newestPhoto.absolutePath, (resolver.resolve(WidgetActionTarget.SEND_LAST_PHOTO) as LatestMediaResult.Found).file.absolutePath)
        assertEquals(newestShot.absolutePath, (resolver.resolve(WidgetActionTarget.SEND_LAST_SCREENSHOT) as LatestMediaResult.Found).file.absolutePath)
        assertEquals(newestVideo.absolutePath, (resolver.resolve(WidgetActionTarget.SEND_LAST_VIDEO) as LatestMediaResult.Found).file.absolutePath)
        assertEquals(newestDownload.absolutePath, (resolver.resolve(WidgetActionTarget.SEND_LAST_DOWNLOAD) as LatestMediaResult.Found).file.absolutePath)
    }

    @Test
    fun returnsNotFoundWhenFoldersAreEmpty() {
        val resolver = LatestMediaResolver(
            hasFullAccess = { true },
            storageRootsProvider = { listOf(tempFolder.root) }
        )
        assertTrue(resolver.resolve(WidgetActionTarget.SEND_LAST_DOWNLOAD) is LatestMediaResult.NotFound)
    }

    private fun createTimedFile(dir: File, name: String, modifiedMs: Long): File =
        File(dir, name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(modifiedMs)
        }
}
