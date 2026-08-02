package com.fileapex.domain.browse

import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.data.transfer.FileTransferService
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.PinSessionRequiredException
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.PathUtils

/**
 * Single source of truth for explorer directory listing and path navigation within a browse root.
 * Path security delegates to [PathUtils].
 */
class BrowserCoordinator(
    private val target: BrowseTarget,
    private val transfer: FileTransferService
) {
    val browseRoot: String = PathUtils.normalize(target.rootPath)
    val isRemote: Boolean = target is BrowseTarget.Remote
    private val remotePinRequired: Boolean =
        (target as? BrowseTarget.Remote)?.pinRequired == true

    fun ensureBrowseAccess() {
        if (!isRemote || !remotePinRequired) return
        if (DeviceSessionManager.isSessionValid(target.deviceId)) return
        throw PinSessionRequiredException()
    }

    suspend fun listAt(path: String): BrowseListing {
        ensureBrowseAccess()
        return when (val browseTarget = target) {
            is BrowseTarget.Local -> {
                val listing = transfer.listLocal(path)
                BrowseListing(
                    directories = listing.directories,
                    files = listing.files
                )
            }
            is BrowseTarget.Remote -> {
                DeviceSessionManager.markDeviceAccessed(browseTarget.deviceId)
                val items = transfer.listRemote(browseTarget.host, browseTarget.port, path)
                BrowseListing(
                    directories = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() },
                    files = items.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
                )
            }
        }
    }

    fun resolveWithinRoot(path: String): String =
        PathUtils.resolveWithinRoot(path, browseRoot)

    fun isWithinRoot(path: String): Boolean =
        PathUtils.isWithinRoot(path, browseRoot)

    fun parentWithinRoot(path: String): String? =
        PathUtils.parentWithinRoot(path, browseRoot)

    fun normalizePath(path: String): String =
        PathUtils.normalizeOr(path, browseRoot)

    fun clipboardCanPaste(selectionMode: Boolean): Boolean {
        return TransferClipboard.hasContent() && !selectionMode
    }
}

data class BrowseListing(
    val directories: List<RemoteFileItem>,
    val files: List<RemoteFileItem>
)
