package com.omninode.domain.transfer

import com.omninode.data.clipboard.TransferClipboard
import com.omninode.data.identity.LocalIdentity
import com.omninode.domain.model.RemoteFileItem
import com.omninode.util.NetworkUtils
import com.omninode.presentation.BrowseTarget

/**
 * Explorer-facing transfer actions. All work goes through [TransferManager] (M7).
 */
class ExplorerTransferManager(
    private val target: BrowseTarget,
    private val transferManager: TransferManager,
    private val identityProvider: () -> LocalIdentity
) {
    fun copySelected(items: List<RemoteFileItem>): String {
        require(items.isNotEmpty()) { "Select at least one file to copy" }
        when (target) {
            is BrowseTarget.Local -> {
                val host = NetworkUtils.preferredLanIpv4()
                transferManager.copyLocalFiles(identityProvider(), items, host)
            }
            is BrowseTarget.Remote -> {
                transferManager.copyRemoteFiles(
                    sourceDeviceId = target.deviceId,
                    sourceDeviceName = target.displayName,
                    host = target.host,
                    port = target.port,
                    items = items
                )
            }
        }
        return if (items.size == 1) {
            "Copied ${items.first().name} — open a folder and tap Paste here"
        } else {
            "Copied ${items.size} files — open a folder and tap Paste here"
        }
    }

    suspend fun pasteInto(currentPath: String): List<String> {
        return when (target) {
            is BrowseTarget.Local -> transferManager.pasteIntoLocal(currentPath)
            is BrowseTarget.Remote -> transferManager.pasteIntoRemote(
                host = target.host,
                port = target.port,
                targetDirectory = currentPath
            )
        }
    }

    suspend fun downloadRemote(items: List<RemoteFileItem>): List<String> {
        val remote = target as? BrowseTarget.Remote
            ?: error("Download is only available for remote devices")
        require(items.isNotEmpty()) { "Select at least one file to download" }
        return transferManager.downloadRemoteToDownloads(remote.host, remote.port, items)
    }

    suspend fun buildMultiCopyOptions(): List<MultiCopyDeviceOption> {
        val sourceDeviceId = when (val browseTarget = target) {
            is BrowseTarget.Local -> identityProvider().deviceId
            is BrowseTarget.Remote -> browseTarget.deviceId
        }
        return transferManager.buildInAppDeviceOptions(sourceDeviceId)
    }

    fun sourcesFrom(items: List<RemoteFileItem>): List<MultiCopySource> {
        return items.map { item ->
            when (target) {
                is BrowseTarget.Local -> MultiCopySource.Local(
                    fileName = item.name,
                    sizeBytes = item.sizeBytes,
                    absolutePath = item.absolutePath
                )
                is BrowseTarget.Remote -> MultiCopySource.Remote(
                    fileName = item.name,
                    sizeBytes = item.sizeBytes,
                    absolutePath = item.absolutePath,
                    host = target.host,
                    port = target.port
                )
            }
        }
    }

    suspend fun sendToDevices(
        sources: List<MultiCopySource>,
        destinations: List<MultiCopyDeviceOption>
    ): TransferBatchResult = transferManager.sendToDevices(sources, destinations)

    fun clipboardLabel(): String? = TransferClipboard.label()

    fun clipboardHasContent(): Boolean = TransferClipboard.hasContent()
}
