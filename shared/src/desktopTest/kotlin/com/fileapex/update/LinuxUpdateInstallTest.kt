package com.fileapex.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxUpdateInstallTest {
    private fun asset(name: String) = GitHubReleaseAsset(name, "https://example.invalid/$name", 10L)

    private val release = listOf(
        asset("FileApex-v0.17.1.apk"),
        asset("FileApex-v0.17.1-Silicon.dmg"),
        asset("FileApex-v0.17.1.exe"),
        asset("FileApex-v0.17.1-amd64.deb"),
        asset("FileApex-v0.17.1-x86_64.rpm"),
        asset("FileApex-v0.17.1-x86_64.AppImage"),
    )

    @Test
    fun eachInstallTypePicksItsOwnFile() {
        assertEquals("FileApex-v0.17.1-x86_64.AppImage", linuxAssetFor(release, LinuxInstallKind.AppImage, "amd64")?.name)
        assertEquals("FileApex-v0.17.1-amd64.deb", linuxAssetFor(release, LinuxInstallKind.Deb, "amd64")?.name)
        assertEquals("FileApex-v0.17.1-x86_64.rpm", linuxAssetFor(release, LinuxInstallKind.Rpm, "amd64")?.name)
    }

    @Test
    fun aReleaseWithoutThePackageIsNotAnUpdate() {
        val noPackages = release.filter { it.name.endsWith(".apk") || it.name.endsWith(".exe") || it.name.endsWith(".dmg") }
        assertNull(linuxAssetFor(noPackages, LinuxInstallKind.Deb, "amd64"))
        assertNull(linuxAssetFor(noPackages, LinuxInstallKind.Rpm, "amd64"))
        assertNull(linuxAssetFor(noPackages, LinuxInstallKind.AppImage, "amd64"))
        assertNull(linuxAssetFor(release.filterNot { it.name.endsWith(".deb") }, LinuxInstallKind.Deb, "amd64"))
    }

    @Test
    fun theOtherCpuIsNeverOffered() {
        assertNull(linuxAssetFor(release, LinuxInstallKind.Deb, "aarch64"))
        assertNull(linuxAssetFor(release, LinuxInstallKind.AppImage, "arm64"))
        val arm = listOf(asset("FileApex-v0.17.1-arm64.deb"), asset("FileApex-v0.17.1-aarch64.AppImage"))
        assertEquals("FileApex-v0.17.1-arm64.deb", linuxAssetFor(arm, LinuxInstallKind.Deb, "aarch64")?.name)
        assertEquals("FileApex-v0.17.1-aarch64.AppImage", linuxAssetFor(arm, LinuxInstallKind.AppImage, "arm64")?.name)
    }

    @Test
    fun unknownInstallGetsNothing() {
        assertNull(linuxAssetFor(release, LinuxInstallKind.Other, "amd64"))
    }

    @Test
    fun newAppImageReplacesTheOldOneUnderItsOwnName() {
        val dir = Files.createTempDirectory("fileapex-update-test").toFile()
        try {
            val current = File(dir, "FileApex-v0.17.0-x86_64.AppImage").apply { writeText("old") }
            val downloaded = File(dir, "download.bin").apply { writeText("new build") }
            val updated = replaceAppImage(current, downloaded, "FileApex-v0.17.1-x86_64.AppImage")
            assertEquals("FileApex-v0.17.1-x86_64.AppImage", updated.name)
            assertEquals("new build", updated.readText())
            assertTrue(updated.canExecute())
            assertFalse(current.exists())
            assertFalse(File(dir, "FileApex-v0.17.1-x86_64.AppImage.update").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sameNameIsReplacedInPlace() {
        val dir = Files.createTempDirectory("fileapex-update-test").toFile()
        try {
            val current = File(dir, "FileApex.AppImage").apply { writeText("old") }
            val downloaded = File(dir, "download.bin").apply { writeText("new build") }
            val updated = replaceAppImage(current, downloaded, "FileApex.AppImage")
            assertEquals(current.canonicalPath, updated.canonicalPath)
            assertEquals("new build", current.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNameWithAPathIsRefused() {
        val dir = Files.createTempDirectory("fileapex-update-test").toFile()
        try {
            val current = File(dir, "FileApex.AppImage").apply { writeText("old") }
            val downloaded = File(dir, "download.bin").apply { writeText("new") }
            replaceAppImage(current, downloaded, "../evil.AppImage")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPackageIsSavedToTheDownloadsFolderAndTheCopyIsRemoved() {
        val dir = Files.createTempDirectory("fileapex-update-test").toFile()
        try {
            val cache = File(dir, "cache").apply { mkdirs() }
            val downloads = File(dir, "Downloads")
            val downloaded = File(cache, "v0.17.1-FileApex-v0.17.1-amd64.deb").apply { writeText("package bytes") }
            val saved = saveUpdateToFolder(downloaded, downloads, "FileApex-v0.17.1-amd64.deb")
            assertEquals(File(downloads, "FileApex-v0.17.1-amd64.deb").canonicalPath, saved.canonicalPath)
            assertEquals("package bytes", saved.readText())
            assertFalse(downloaded.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
