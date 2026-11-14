package com.fileapex.domain.backup

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.SettingsKvStore
import com.fileapex.domain.clipboard.ClipboardImageEnvelope
import com.fileapex.domain.clipboard.ClipboardImageStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Phase5BackupAndClipboardImageTest {
    @Test
    fun backupRoutingSeparatesPhotosAndScreenshotsBySourceDevice() {
        assertEquals(
            "@inbox/Pixel 9/Photos/IMG_001.jpg",
            BackupFolderPresets.remoteTargetPath(
                destinationRoot = "@inbox",
                sourceDeviceName = "Pixel 9",
                sourcePath = "/storage/emulated/0/DCIM/Camera",
                fileRelativePath = "Camera/IMG_001.jpg"
            )
        )
        assertEquals(
            "@inbox/Pixel 9/Screenshots/Screenshot_1.png",
            BackupFolderPresets.remoteTargetPath(
                destinationRoot = "@inbox",
                sourceDeviceName = "Pixel 9",
                sourcePath = "/storage/emulated/0/DCIM/Screenshots",
                fileRelativePath = "Screenshots/Screenshot_1.png"
            )
        )
        assertEquals(
            "@inbox/Galaxy_S24/Screenshots/Shot.png",
            BackupFolderPresets.remoteTargetPath(
                destinationRoot = "@inbox",
                sourceDeviceName = "Galaxy/S24",
                sourcePath = "/storage/emulated/0/Pictures/Screenshots",
                fileRelativePath = "Screenshots/Shot.png"
            )
        )
        assertEquals(
            "@inbox/Pixel 9/Documents/receipt.pdf",
            BackupFolderPresets.remoteTargetPath(
                destinationRoot = "@inbox",
                sourceDeviceName = "Pixel 9",
                sourcePath = "/storage/emulated/0/Documents",
                fileRelativePath = "Documents/receipt.pdf"
            )
        )
    }

    @Test
    fun androidPresetsDetectBothOemScreenshotLocationsWithoutDuplicates() {
        val samsungPaths = setOf(
            "/storage/emulated/0/DCIM/Camera",
            "/storage/emulated/0/DCIM/Screenshots"
        )
        val detectedSamsung = BackupFolderPresets.detectAndroidMediaPresets("/storage/emulated/0") {
            it in samsungPaths
        }
        assertEquals(
            listOf(
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0/DCIM/Screenshots"
            ),
            detectedSamsung.map { it.path }
        )

        val pixelPaths = setOf(
            "/storage/emulated/0/DCIM/Camera",
            "/storage/emulated/0/Pictures/Screenshots"
        )
        val detectedPixel = BackupFolderPresets.detectAndroidMediaPresets("/storage/emulated/0") {
            it in pixelPaths
        }
        assertEquals(
            listOf(
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0/Pictures/Screenshots"
            ),
            detectedPixel.map { it.path }
        )
    }

    @Test
    fun clipboardImageEnvelopeRoundTripsBoundedBytesAndRejectsOversize() {
        val sample = byteArrayOf(1, 2, 3, 4, 5, 6)
        val encoded = ClipboardImageEnvelope.encode(sample, "image/png")
        assertNotNull(encoded)
        assertTrue(ClipboardImageEnvelope.isEnvelope(encoded!!))
        val decoded = ClipboardImageEnvelope.decode(encoded)
        assertNotNull(decoded)
        assertEquals("image/png", decoded!!.mimeType)
        assertArrayEquals(sample, decoded.bytes)

        val keyA = ClipboardImageEnvelope.dedupeKey(sample)
        val keyB = ClipboardImageEnvelope.dedupeKey(byteArrayOf(1, 2, 3, 4, 5, 7))
        assertNotEquals(keyA, keyB)

        val oversize = ByteArray(ClipboardImageEnvelope.MAX_RAW_IMAGE_BYTES + 1) { 7 }
        assertNull(ClipboardImageEnvelope.encode(oversize, "image/png"))
        assertNull(ClipboardImageEnvelope.decode("plain text clipboard"))
    }

    private class LocalMemoryKvStore : SettingsKvStore {
        private val data = mutableMapOf<String, Any>()
        override fun contains(key: String): Boolean = key in data
        override fun getBoolean(key: String, default: Boolean): Boolean = (data[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { data[key] = value }
        override fun getString(key: String, default: String): String = (data[key] as? String) ?: default
        override fun putString(key: String, value: String) { data[key] = value }
        override fun getInt(key: String, default: Int): Int = (data[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { data[key] = value }
        override fun getLong(key: String, default: Long): Long = (data[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { data[key] = value }
        override fun keys(prefix: String): List<String> = data.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { data.remove(key) }
        override fun clear() { data.clear() }
    }

    @Test
    fun clipboardImagesEnabledSettingDefaultsTruePersistsAndResets() {
        val store = LocalMemoryKvStore()
        val settings = BaseAppSettings(store)
        assertTrue(settings.clipboardImagesEnabled.value)

        val sample = byteArrayOf(10, 20, 30)
        assertNotNull(ClipboardImageEnvelope.encodeIfEnabled(sample, settings.clipboardImagesEnabled.value))

        settings.setClipboardImagesEnabled(false)
        assertFalse(settings.clipboardImagesEnabled.value)
        assertFalse(BaseAppSettings(store, existingInstall = true).clipboardImagesEnabled.value)
        assertNull(ClipboardImageEnvelope.encodeIfEnabled(sample, settings.clipboardImagesEnabled.value))

        settings.resetToDefaults()
        assertTrue(settings.clipboardImagesEnabled.value)
    }

    @Test
    fun clipboardImageStorageSavesSanitizedUniqueFileToDirectory() {
        val dir = Files.createTempDirectory("clip-img-save").toFile()
        try {
            val pngBytes = byteArrayOf(1, 2, 3, 4)
            val saved1 = ClipboardImageStorage.saveToDirectory(
                bytes = pngBytes,
                mimeType = "image/png",
                sourceDeviceName = "Pixel/9 Pro",
                destinationDir = dir.absolutePath,
                nowEpochMs = 1_700_000_000_000L
            )
            assertNotNull(saved1)
            val file1 = File(saved1!!)
            assertTrue(file1.exists())
            assertTrue(file1.name.endsWith(".png"))
            assertTrue(file1.name.contains("Pixel_9 Pro"))
            assertArrayEquals(pngBytes, file1.readBytes())

            val jpgBytes = byteArrayOf(5, 6, 7)
            val saved2 = ClipboardImageStorage.saveToDirectory(
                bytes = jpgBytes,
                mimeType = "image/png",
                sourceDeviceName = "Pixel/9 Pro",
                destinationDir = dir.absolutePath,
                nowEpochMs = 1_700_000_000_000L
            )
            assertNotNull(saved2)
            assertNotEquals(saved1, saved2)
            assertTrue(File(saved2!!).name.contains("(1).png"))

            assertNull(
                ClipboardImageStorage.saveToDirectory(
                    bytes = byteArrayOf(),
                    mimeType = "image/jpeg",
                    sourceDeviceName = "Mac",
                    destinationDir = dir.absolutePath,
                    nowEpochMs = 1_700_000_000_000L
                )
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backupSuggestionPolicyOnlyPromptsAndroidWithDesktopPeerWhenUnconfiguredAndNotDismissed() {
        val desktops = listOf(BackupSuggestionPolicy.DesktopPeer(deviceId = "mac-1", name = "Mac mini"))
        val baseConfig = BackupConfig()

        assertEquals(
            "mac-1",
            BackupSuggestionPolicy.targetDesktop(isAndroid = true, pairedDesktops = desktops, config = baseConfig)?.deviceId
        )
        assertNull(BackupSuggestionPolicy.targetDesktop(isAndroid = false, pairedDesktops = desktops, config = baseConfig))
        assertNull(BackupSuggestionPolicy.targetDesktop(isAndroid = true, pairedDesktops = emptyList(), config = baseConfig))
        assertNull(
            BackupSuggestionPolicy.targetDesktop(
                isAndroid = true,
                pairedDesktops = desktops,
                config = baseConfig.copy(enabled = true)
            )
        )
        assertNull(
            BackupSuggestionPolicy.targetDesktop(
                isAndroid = true,
                pairedDesktops = desktops,
                config = baseConfig.copy(promptDismissed = true)
            )
        )

        val enabledConfig = BackupSuggestionPolicy.enableSuggestedConfig(
            current = baseConfig,
            desktopDeviceId = "mac-1",
            presetPaths = listOf("/storage/emulated/0/DCIM/Camera", "/storage/emulated/0/Pictures/Screenshots")
        )
        assertTrue(enabledConfig.enabled)
        assertEquals("mac-1", enabledConfig.destinationDeviceId)
        assertTrue(enabledConfig.wifiOnly)
        assertEquals(2, enabledConfig.sources.size)
        assertEquals(enabledConfig.copy(promptDismissed = true), BackupConfig.decode(enabledConfig.copy(promptDismissed = true).encode()))
    }

    @Test
    fun backupStatusLabelPolicyFormatsIdleAndRunningStates() {
        assertEquals(
            BackupStatusKind.Running(done = 2, total = 5),
            BackupStatusLabelPolicy.classify(
                config = BackupConfig(enabled = true, destinationDeviceId = "mac-1", sources = listOf("/dcim")),
                status = BackupStatus.Running(done = 1, total = 5, current = "IMG_1.jpg"),
                lastRun = null
            )
        )
        assertEquals(
            BackupStatusKind.Synced(uploaded = 12, failed = 0),
            BackupStatusLabelPolicy.classify(
                config = BackupConfig(enabled = true, destinationDeviceId = "mac-1", sources = listOf("/dcim")),
                status = BackupStatus.Idle,
                lastRun = BackupSummary(atMillis = 123L, uploaded = 12, failed = 0, error = null)
            )
        )
        assertEquals(
            BackupStatusKind.NeedsAttention(failed = 2),
            BackupStatusLabelPolicy.classify(
                config = BackupConfig(enabled = true, destinationDeviceId = "mac-1", sources = listOf("/dcim")),
                status = BackupStatus.Idle,
                lastRun = BackupSummary(atMillis = 123L, uploaded = 10, failed = 2, error = "Timeout")
            )
        )
        assertEquals(
            BackupStatusKind.Ready,
            BackupStatusLabelPolicy.classify(
                config = BackupConfig(enabled = true, destinationDeviceId = "mac-1", sources = listOf("/dcim")),
                status = BackupStatus.Idle,
                lastRun = null
            )
        )
    }
}
