package com.fileapex.domain.demo

import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceListRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object DemoModeState {
    private val _isDemoModeActive = MutableStateFlow(false)
    val isDemoModeActive = _isDemoModeActive.asStateFlow()

    fun launchDemo() {
        _isDemoModeActive.value = true
    }

    fun exitDemo() {
        _isDemoModeActive.value = false
    }

    fun getDemoDeviceRows(): List<DeviceListRow> = listOf(
        DeviceListRow(
            deviceId = "demo_macbook",
            deviceName = "MacBook Pro 16\"",
            online = true,
            appVersion = "0.14.1a",
            appVersionCode = 163,
            os = "macOS",
            platform = "Desktop",
            deviceMake = "Apple",
            deviceModel = "MacBookPro18,1"
        ),
        DeviceListRow(
            deviceId = "demo_tablet",
            deviceName = "Pixel Tablet",
            online = true,
            appVersion = "0.14.1a",
            appVersionCode = 163,
            os = "Android",
            platform = "Android",
            deviceMake = "Google",
            deviceModel = "Pixel Tablet"
        )
    )

    fun getBrowseTarget(deviceId: String): BrowseTarget.Demo {
        val name = if (deviceId == "demo_tablet") "Pixel Tablet" else "MacBook Pro 16\""
        return BrowseTarget.Demo(deviceId = deviceId, displayName = name)
    }

    fun getDemoDirectories(path: String): List<RemoteFileItem> {
        val normalized = path.trimEnd('/')
        return if (normalized.isEmpty() || normalized == "/") {
            listOf(
                RemoteFileItem(
                    id = "demo_dir_documents",
                    name = "Documents",
                    absolutePath = "/Documents",
                    sizeBytes = 0L,
                    lastModified = 1775000000000L,
                    isDirectory = true,
                    mimeType = "inode/directory"
                ),
                RemoteFileItem(
                    id = "demo_dir_photos",
                    name = "Photos",
                    absolutePath = "/Photos",
                    sizeBytes = 0L,
                    lastModified = 1775000000000L,
                    isDirectory = true,
                    mimeType = "inode/directory"
                ),
                RemoteFileItem(
                    id = "demo_dir_notes",
                    name = "Notes",
                    absolutePath = "/Notes",
                    sizeBytes = 0L,
                    lastModified = 1775000000000L,
                    isDirectory = true,
                    mimeType = "inode/directory"
                )
            )
        } else {
            emptyList()
        }
    }

    fun getDemoFiles(path: String): List<RemoteFileItem> {
        val normalized = path.trimEnd('/')
        return when (normalized) {
            "/Documents" -> listOf(
                RemoteFileItem(
                    id = "demo_file_pdf",
                    name = "Project_Summary.pdf",
                    absolutePath = "/Documents/Project_Summary.pdf",
                    sizeBytes = 142_000L,
                    lastModified = 1775000000000L,
                    isDirectory = false,
                    mimeType = "application/pdf"
                )
            )
            "/Photos" -> listOf(
                RemoteFileItem(
                    id = "demo_file_jpg",
                    name = "Sample_Photo.jpg",
                    absolutePath = "/Photos/Sample_Photo.jpg",
                    sizeBytes = 85_000L,
                    lastModified = 1775000000000L,
                    isDirectory = false,
                    mimeType = "image/jpeg"
                )
            )
            "/Notes" -> listOf(
                RemoteFileItem(
                    id = "demo_file_txt",
                    name = "Quick_Demo.txt",
                    absolutePath = "/Notes/Quick_Demo.txt",
                    sizeBytes = 12_000L,
                    lastModified = 1775000000000L,
                    isDirectory = false,
                    mimeType = "text/plain"
                )
            )
            else -> emptyList()
        }
    }

    const val DEMO_TXT_CONTENT: String = """Welcome to FileApex!

This is a simulated demo file showing how FileApex transfers documents and media across your local Wi-Fi without cloud servers or tracking.

Pair your real devices to transfer at full LAN speed."""

    const val DEMO_PDF_CONTENT: String = """FileApex — Local P2P Ecosystem File Manager

FileApex seamlessly syncs, manages, and broadcasts files across Android devices, Mac, and Windows.

Key Features:
• Local-first P2P Explorer: Browse and move files between paired Android, macOS, and Windows devices on the same LAN.
• Multi-Target File Broadcasting: Push files to multiple online devices simultaneously.
• Smart Receive Folder: Incoming files automatically route to Download/FileApex/ (Android) or ~/Downloads/FileApex/ (Desktop).
• QR Code or Local Join: Pair devices instantly without typing IP addresses.
• Cross-Platform Clipboard Sharing: Sync text and URLs across all paired devices (opt-in).
• Bulletin Board: Send text messages and file attachments in one shared hub.
• Privacy & Security: File transfers stay 100% on your local Wi-Fi network with zero cloud storage dependencies."""

    const val DEMO_PHOTO_CONTENT: String = """Sample Photo (Demo Mode)

File Name: Sample_Photo.jpg
Size: 85 KB
Format: JPEG Image

Tap 'Download' below to simulate a real-time LAN transfer to this device with live speed and progress tracking."""

    fun getDemoPreviewText(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) || fileName.endsWith(".png", ignoreCase = true) -> DEMO_PHOTO_CONTENT
            fileName.endsWith(".pdf", ignoreCase = true) -> DEMO_PDF_CONTENT
            fileName.endsWith(".txt", ignoreCase = true) -> DEMO_TXT_CONTENT
            else -> "Sample preview for $fileName (Demo Mode)"
        }
    }

    fun getDemoPreviewBytes(fileName: String): ByteArray {
        return getDemoPreviewText(fileName).encodeToByteArray()
    }

    fun simulateTransfer(
        item: RemoteFileItem,
        scope: CoroutineScope,
        onComplete: () -> Unit = {}
    ) {
        scope.launch(Dispatchers.Default) {
            TransferActivityGuard.beginTransfer(
                fileName = item.name,
                destinationDeviceName = "MacBook Pro 16\"",
                deviceId = "demo_device"
            )
            try {
                val totalBytes = item.sizeBytes
                val steps = 10
                for (i in 1..steps) {
                    val progressBytes = (totalBytes * i) / steps
                    TransferActivityGuard.updateProgress(
                        sentBytes = progressBytes,
                        totalBytes = totalBytes,
                        deviceId = "demo_device"
                    )
                    delay(150L)
                }
            } finally {
                TransferActivityGuard.endTransfer("demo_device")
                launch(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }
}
