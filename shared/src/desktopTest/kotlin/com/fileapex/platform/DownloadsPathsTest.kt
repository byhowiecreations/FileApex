package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsPathsTest {

    @Test
    fun androidFallbackFromRootDefaultsToCanonicalStorage() {
        val pathFromSlash = DownloadsPaths.fallbackFromRoot("/", "Android")
        assertEquals("/storage/emulated/0/Download/FileApex", pathFromSlash)

        val pathFromBlank = DownloadsPaths.fallbackFromRoot("", "android")
        assertEquals("/storage/emulated/0/Download/FileApex", pathFromBlank)

        val pathFromExplicit = DownloadsPaths.fallbackFromRoot("/storage/emulated/0", "Android")
        assertEquals("/storage/emulated/0/Download/FileApex", pathFromExplicit)

        assertFalse(pathFromSlash.contains("/Users/"))
        assertFalse(pathFromSlash.contains(":\\"))
    }

    @Test
    fun resolveReceiveRootProtectsAndroidFromDesktopPaths() {
        val resolved = DownloadsPaths.resolveReceiveRoot(
            downloadsPath = "/Users/cliff/Downloads/FileApex",
            rootPath = "/",
            platform = "Android"
        )
        assertEquals("/storage/emulated/0/Download/FileApex", resolved)
    }

    @Test
    fun desktopFallbackResolvesDesktopDirectory() {
        val path = DownloadsPaths.fallbackFromRoot("", "Desktop")
        assertTrue(path.endsWith("FileApex"))
    }
}
