package com.fileapex.presentation.explorer

import com.fileapex.presentation.TransferDropUri
import androidx.lifecycle.viewModelScope
import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.di.FileApexServices
import com.fileapex.domain.browse.BrowserCoordinator
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.presence.RouteUse
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.platform.moveLocalEntryInto
import com.fileapex.platform.renameLocalEntry
import com.fileapex.platform.trashLocalEntries
import com.fileapex.platform.unzipLocalEntry
import com.fileapex.platform.zipLocalEntry
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ExplorerMutationActions(
    private val _uiState: MutableStateFlow<ExplorerUiState>,
    private val viewModelScope: CoroutineScope,
    private val target: BrowseTarget,
    private val browser: BrowserCoordinator,
    private val browseRoot: String,
    private val remoteDeviceId: String?,
    private val refresh: () -> Unit,
    private val selectedItems: () -> List<RemoteFileItem>
) {
    fun renameItem(item: RemoteFileItem, newName: String) {
        if (target !is BrowseTarget.Local) return
        viewModelScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                runCatching { renameLocalEntry(item.absolutePath, newName) }
            }
            renamed.onSuccess { afterMutation() }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "unable_to_open_folder")) }
                }
        }
    }

    fun compressItem(item: RemoteFileItem) {
        if (target !is BrowseTarget.Local) return
        viewModelScope.launch {
            val zipped = withContext(Dispatchers.IO) {
                runCatching { zipLocalEntry(item.absolutePath) }
            }
            zipped.onSuccess { afterMutation() }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "unable_to_open_folder")) }
                }
        }
    }

    fun uncompressItem(item: RemoteFileItem) {
        if (target !is BrowseTarget.Local) return
        viewModelScope.launch {
            val unpacked = withContext(Dispatchers.IO) {
                runCatching { unzipLocalEntry(item.absolutePath) }
            }
            unpacked.onSuccess { afterMutation() }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "unable_to_open_folder")) }
                }
        }
    }

    /** Refreshes this folder and tells the Tools lists their data changed. */
    private fun afterMutation() {
        refresh()
        com.fileapex.platform.StorageToolsEvents.changed.tryEmit(Unit)
    }

    fun moveItem(item: RemoteFileItem, destination: String) {
        if (target !is BrowseTarget.Local) return
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems().ifEmpty { listOf(item) }
        } else {
            listOf(item)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { items.forEach { com.fileapex.platform.moveLocalEntryInto(it.absolutePath, destination) } }
            }
            outcome.onSuccess {
                _uiState.update {
                    it.copy(
                        isSelectionMode = false,
                        selectedFileIds = emptySet(),
                        canDownloadSelection = false,
                        statusMessage = AppI18n.t("moved_ok")
                    )
                }
                afterMutation()
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "unable_to_open_folder")) }
            }
        }
    }

    private fun deleteRemoteItems(remote: BrowseTarget.Remote, item: RemoteFileItem) {
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems().ifEmpty { listOf(item) }
        } else {
            listOf(item)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                cancellableCatching {
                    browser.ensureBrowseAccess()
                    items.forEach { FileApexServices.client.deleteRemote(remote.host, remote.port, it.absolutePath) }
                }
            }
            outcome.onSuccess {
                _uiState.update {
                    it.copy(
                        isSelectionMode = false,
                        selectedFileIds = emptySet(),
                        canDownloadSelection = false,
                        statusMessage = AppI18n.t("remote_deleted")
                    )
                }
                refresh()
            }.onFailure { error ->
                // Some of a multi-selection may already be gone, so show what the device really has.
                _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "trash_failed")) }
                refresh()
            }
        }
    }

    fun deleteItem(item: RemoteFileItem) {
        (target as? BrowseTarget.Remote)?.let { return deleteRemoteItems(it, item) }
        if (target !is BrowseTarget.Local) return
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems().ifEmpty { listOf(item) }
        } else {
            listOf(item)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { trashLocalEntries(items.map { it.absolutePath }) }
            }
            outcome.onSuccess { finished ->
                TransferClipboard.dropPaths(items.map { it.absolutePath }.toSet())
                _uiState.update {
                    it.copy(
                        isSelectionMode = false,
                        selectedFileIds = emptySet(),
                        canDownloadSelection = false,
                        statusMessage = if (finished) AppI18n.t("moved_to_trash") else null,
                        clipboardLabel = TransferClipboard.label(),
                        canPaste = TransferClipboard.hasContent()
                    )
                }
                if (finished) afterMutation()
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "trash_failed")) }
            }
        }
    }

    fun importDropped(paths: List<String>, destinationDirectory: String? = null) {
        if (paths.isEmpty()) return
        val destination = destinationDirectory?.takeIf { it.isNotBlank() }
            ?: _uiState.value.currentPath.ifBlank { browseRoot }
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                when (val browseTarget = target) {
                    is BrowseTarget.Local -> paths.mapNotNull { payload ->
                        cancellableCatching { dropOntoLocal(payload, destination) }.exceptionOrNull()
                    }
                    is BrowseTarget.Remote -> {
                        val localPaths = paths.mapNotNull { localPathOfDrop(it) }
                        if (localPaths.isEmpty()) emptyList()
                        else cancellableCatching {
                            val sources = com.fileapex.domain.transfer.LocalTransferTree.expandAbsolutePaths(localPaths)
                            FileApexServices.transferService.multiCopyToDevices(
                                sources,
                                listOf(
                                    MultiCopyDeviceOption(
                                        deviceId = browseTarget.deviceId,
                                        deviceName = browseTarget.displayName,
                                        isLocal = false,
                                        host = browseTarget.host,
                                        port = browseTarget.port,
                                        destinationRoot = destination
                                    )
                                )
                            )
                        }.exceptionOrNull()?.let { listOf(it) }.orEmpty()
                    }
                    is BrowseTarget.Demo -> emptyList()
                }
            }
            if (failed.isNotEmpty()) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingErrors.message(failed.first(), "unable_to_open_folder"))
                }
            }
            refresh()
        }
    }

    private suspend fun dropOntoLocal(payload: String, destination: String) {
        val parsed = parseDropPayload(payload)
        if (parsed.remoteDeviceId == null) {
            moveLocalEntryInto(parsed.path, destination)
            return
        }
        val device = FileApexServices.deviceRepository.getDevice(parsed.remoteDeviceId) ?: error("device missing")
        val endpoint = FileApexServices.presenceMonitor.probeNow(device, RouteUse.Bulk)
        FileApexServices.transferService.downloadRemoteToDownloads(
            host = endpoint?.host ?: device.lastKnownIp,
            port = endpoint?.port ?: device.port,
            items = listOf(
                RemoteFileItem(
                    name = parsed.name,
                    absolutePath = parsed.path,
                    sizeBytes = parsed.size,
                    lastModified = 0L,
                    isDirectory = false,
                    mimeType = "application/octet-stream"
                )
            ),
            destinationDirectory = destination
        )
    }

    private data class DropPayload(
        val path: String,
        val name: String,
        val size: Long,
        val remoteDeviceId: String?
    )

    private fun parseDropPayload(payload: String): DropPayload {
        val parsed = TransferDropUri.parse(payload)
            ?: return DropPayload(payload, payload.substringAfterLast('/'), 0L, null)
        return DropPayload(parsed.path, parsed.name, parsed.size, parsed.sourceDeviceId.takeUnless { parsed.isLocal })
    }

    private fun localPathOfDrop(payload: String): String? {
        val parsed = parseDropPayload(payload)
        return if (parsed.remoteDeviceId == null) parsed.path else null
    }
}
