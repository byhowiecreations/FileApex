package com.fileapex.presentation

import com.fileapex.presentation.explorer.ExplorerPreviewActions
import com.fileapex.presentation.explorer.ExplorerMutationActions
import com.fileapex.presentation.explorer.ExplorerCopyActions
import com.fileapex.presentation.explorer.ExplorerSelectionActions
import com.fileapex.presentation.explorer.ExplorerWatchActions
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.domain.presence.RouteUse
import com.fileapex.domain.browse.BrowseListing
import com.fileapex.domain.browse.BrowserCoordinator
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.domain.transfer.ExplorerTransferManager
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.platform.DownloadsPaths
import com.fileapex.platform.decodeImageBytes
import com.fileapex.platform.decodeLocalImageFile
import com.fileapex.platform.DirectoryWatch
import com.fileapex.platform.moveLocalEntryInto
import com.fileapex.platform.renameLocalEntry
import com.fileapex.platform.trashLocalEntries
import com.fileapex.platform.unzipLocalEntry
import com.fileapex.platform.watchLocalDirectory
import com.fileapex.platform.zipLocalEntry
import com.fileapex.platform.previewMaxEdgePx
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class ExplorerUiState(
    val deviceTitle: String = "",
    /** Navigated folder for list, paste, and transfers. Split-pane preview must not change this. */
    val currentPath: String = "",
    /** Folders shown in the wide left pane. */
    val panePath: String = "",
    /** Parent within the browse root only; null means back should leave the explorer. */
    val parentPath: String? = null,
    val canNavigateUp: Boolean = false,
    val paneDirectories: List<RemoteFileItem> = emptyList(),
    val paneFiles: List<RemoteFileItem> = emptyList(),
    val contentDirectories: List<RemoteFileItem> = emptyList(),
    val contentFiles: List<RemoteFileItem> = emptyList(),
    val selectedFolderPath: String? = null,
    val loadingFolderPath: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    /** Local paths from the last completed download, for the snackbar Open / Show-in-folder action. */
    val lastDownloadedPaths: List<String> = emptyList(),
    val clipboardLabel: String? = null,
    val canPaste: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedFileIds: Set<String> = emptySet(),
    val canDownloadSelection: Boolean = false,
    val isRemoteTarget: Boolean = false,
    val previewItem: RemoteFileItem? = null,
    val previewText: String? = null,
    val previewImage: ImageBitmap? = null,
    val isPreviewLoading: Boolean = false,
    val canDownloadPreview: Boolean = false,
    val isDownloading: Boolean = false,
    val showMultiCopyIntro: Boolean = false,
    val showMultiCopyPicker: Boolean = false,
    val multiCopyOptions: List<MultiCopyDeviceOption> = emptyList(),
    val selectedMultiCopyDeviceIds: Set<String> = emptySet(),
    val isMultiCopying: Boolean = false,
    /** When set, explorer must collect PIN before continuing navigation (idle expiry). */
    val pendingPinUnlock: Boolean = false,
    val pinUnlockError: String? = null,
    val viewMode: ExplorerViewMode = ExplorerViewMode.List,
    val sourceDeviceId: String? = null,
)

class ExplorerViewModel(
    private val target: BrowseTarget
) : ViewModel() {
    private val browser = BrowserCoordinator(target, FileApexServices.transferService)
    private val preview = FilePreviewManager(target)
    private val transfers = ExplorerTransferManager(
        target = target,
        transferManager = FileApexServices.transferManager,
        identityProvider = { FileApexServices.localIdentity }
    )
    private val settings = FileApexServices.settings
    private val browseRoot: String = browser.browseRoot

    fun browseRootPath(): String = browseRoot
    private val isRemote: Boolean = browser.isRemote || target is BrowseTarget.Demo
    private val remoteDeviceId: String? = (target as? BrowseTarget.Remote)?.deviceId
    /** Resume after mid-explorer PIN re-entry. */
    private var pendingBrowseAction: (suspend () -> Unit)? = null
    private var browseJob: Job? = null
    private var browseGeneration = 0
    private val _uiState = MutableStateFlow(
        ExplorerUiState(
            deviceTitle = target.displayName,
            clipboardLabel = transfers.clipboardLabel(),
            canPaste = transfers.clipboardHasContent(),
            isRemoteTarget = isRemote,
            sourceDeviceId = remoteDeviceId
        )
    )
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    private val previewActions = ExplorerPreviewActions(_uiState, viewModelScope, target, preview, transfers, isRemote)
    private val mutations = ExplorerMutationActions(_uiState, viewModelScope, target, browser, browseRoot, remoteDeviceId, { refresh() }, { selection.selectedItems() })
    private val copying = ExplorerCopyActions(_uiState, viewModelScope, settings, transfers, { refresh() }, { selection.selectedItems() })
    private val selection = ExplorerSelectionActions(_uiState, viewModelScope, target, preview, isRemote, { onDirectoryClick(it) }, { previewActions.openImagePreview(it) }, { previewActions.openTextPreview(it) })
    private val watching = ExplorerWatchActions(_uiState, viewModelScope, target, browser, browseRoot, { browseGeneration }, { reloadListing(it) })

    init {
        openPath(browseRoot)
        if (target is BrowseTarget.Remote) {
            viewModelScope.launch { browser.revalidated.collect(watching::onFolderRevalidated) }
        }
        if (target is BrowseTarget.Local) {
            viewModelScope.launch {
                com.fileapex.platform.StorageToolsEvents.changed.collect { refresh() }
            }
        }
        viewModelScope.launch {
            settings.explorerViewMode.collect { mode ->
                _uiState.update { it.copy(viewMode = mode) }
            }
        }
        viewModelScope.launch {
            TransferClipboard.payloads.collect { payloads ->
                _uiState.update {
                    it.copy(
                        clipboardLabel = TransferClipboard.label(),
                        canPaste = payloads.isNotEmpty() && !it.isSelectionMode
                    )
                }
            }
        }
    }

    fun openPath(path: String) {
        val resolved = browser.resolveWithinRoot(path)
        val localOnly = target is BrowseTarget.Local
        _uiState.update {
            it.copy(
                isLoading = !localOnly,
                loadingFolderPath = if (localOnly) null else resolved,
                errorMessage = null,
                selectedFolderPath = null,
                previewItem = null,
                previewText = null,
                previewImage = null,
                isPreviewLoading = false,
                canDownloadPreview = false,
                isSelectionMode = false,
                selectedFileIds = emptySet(),
                canDownloadSelection = false,
                canPaste = TransferClipboard.hasContent()
            )
        }
        launchBrowse {
            browseWithPinRetry {
                val listing = browser.listAt(resolved)
                applyPaneAndContent(
                    panePath = resolved,
                    contentPath = resolved,
                    paneDirectories = listing.directories,
                    paneFiles = listing.files,
                    contentDirectories = listing.directories,
                    contentFiles = listing.files,
                    selectedFolderPath = null
                )
            }
        }
    }


    /**
     * Wide left-pane folder: keep sibling list, show that folder's full contents on the right.
     */
    fun onPaneFolderClick(item: RemoteFileItem) {
        if (_uiState.value.isSelectionMode) {
            toggleFileSelection(item)
            return
        }
        if (!browser.isWithinRoot(item.absolutePath)) {
            _uiState.update {
                it.copy(statusMessage = AppI18n.t("folder_outside_root"))
            }
            return
        }
        val resolved = browser.resolveWithinRoot(item.absolutePath)
        val localOnly = target is BrowseTarget.Local
        _uiState.update {
            it.copy(
                isLoading = !localOnly,
                loadingFolderPath = if (localOnly) null else resolved,
                errorMessage = null
            )
        }
        launchBrowse {
            browseWithPinRetry {
                val listing = browser.listAt(resolved)
                applyPaneAndContent(
                    panePath = _uiState.value.panePath.ifBlank { browseRoot },
                    contentPath = resolved,
                    paneDirectories = _uiState.value.paneDirectories,
                    paneFiles = _uiState.value.paneFiles,
                    contentDirectories = listing.directories,
                    contentFiles = listing.files,
                    selectedFolderPath = resolved
                )
            }
        }
    }

    /**
     * Folder inside the content pane (or phone list): drill in; left becomes this folder's parent.
     */
    fun onContentDirectoryClick(item: RemoteFileItem) {
        if (_uiState.value.isSelectionMode) {
            toggleFileSelection(item)
            return
        }
        if (!browser.isWithinRoot(item.absolutePath)) {
            _uiState.update {
                it.copy(statusMessage = AppI18n.t("folder_outside_root"))
            }
            return
        }
        val newContent = browser.resolveWithinRoot(item.absolutePath)
        val localOnly = target is BrowseTarget.Local
        _uiState.update {
            it.copy(
                isLoading = !localOnly,
                loadingFolderPath = if (localOnly) null else newContent,
                errorMessage = null
            )
        }
        launchBrowse {
            browseWithPinRetry {
                val newPane = browser.parentWithinRoot(newContent) ?: browseRoot

                val currentPane = _uiState.value.panePath.ifBlank { browseRoot }
                val (paneListing, contentListing) = if (
                    browser.normalizePath(newPane) == browser.normalizePath(currentPane) &&
                    _uiState.value.paneDirectories.isNotEmpty()
                ) {
                    BrowseListing(
                        directories = _uiState.value.paneDirectories,
                        files = _uiState.value.paneFiles
                    ) to browser.listAt(newContent)
                } else {
                    coroutineScope {
                        val pDeferred = async { browser.listAt(newPane) }
                        val cDeferred = async { browser.listAt(newContent) }
                        pDeferred.await() to cDeferred.await()
                    }
                }

                applyPaneAndContent(
                    panePath = newPane,
                    contentPath = newContent,
                    paneDirectories = paneListing.directories,
                    paneFiles = paneListing.files,
                    contentDirectories = contentListing.directories,
                    contentFiles = contentListing.files,
                    selectedFolderPath = newContent
                )
            }
        }
    }

    fun previewFirstSplitPaneFolder() = Unit

    fun onDirectoryClick(item: RemoteFileItem) {
        onContentDirectoryClick(item)
    }

    private fun launchBrowse(block: suspend () -> Unit) {
        browseGeneration++
        browseJob?.cancel()
        browseJob = viewModelScope.launch { block() }
    }

    private suspend fun browseWithPinRetry(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: PinSessionRequiredException) {
            requestPinThen { browseWithPinRetry(block) }
        } catch (error: Throwable) {
            if (error is PinSessionRequiredException || error.message?.contains("pin_required", ignoreCase = true) == true) {
                requestPinThen { browseWithPinRetry(block) }
                return
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadingFolderPath = null,
                    isRefreshing = false,
                    errorMessage = UserFacingErrors.message(error, "unable_to_open_folder")
                )
            }
        }
    }

    fun cancelPinUnlock() {
        pendingBrowseAction = null
        _uiState.update {
            it.copy(pendingPinUnlock = false, pinUnlockError = null, isLoading = false, loadingFolderPath = null)
        }
    }

    fun confirmPinUnlock(pin: String) {
        val remote = target as? BrowseTarget.Remote ?: return
        viewModelScope.launch {
            cancellableCatching {
                require(pin.isNotBlank()) { AppI18n.t("pin_required_error") }
                FileApexServices.client.verifyPin(
                    host = remote.host,
                    port = remote.port,
                    pin = pin.trim()
                )
                DeviceSessionManager.markDeviceAccessed(remote.deviceId)
                val resume = pendingBrowseAction
                pendingBrowseAction = null
                _uiState.update {
                    it.copy(pendingPinUnlock = false, pinUnlockError = null)
                }
                resume?.invoke()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(pinUnlockError = UserFacingErrors.message(error, "incorrect_pin"))
                }
            }
        }
    }

    private fun requestPinThen(action: suspend () -> Unit) {
        pendingBrowseAction = action
        _uiState.update {
            it.copy(
                pendingPinUnlock = true,
                pinUnlockError = null,
                isLoading = false,
                loadingFolderPath = null,
                isRefreshing = false
            )
        }
    }

    private fun applyPaneAndContent(
        panePath: String,
        contentPath: String,
        paneDirectories: List<RemoteFileItem>,
        paneFiles: List<RemoteFileItem>,
        contentDirectories: List<RemoteFileItem>,
        contentFiles: List<RemoteFileItem>,
        selectedFolderPath: String?
    ) {
        val normalizedContent = browser.normalizePath(contentPath)
        val normalizedPane = browser.normalizePath(panePath)
        val parent = browser.parentWithinRoot(normalizedContent)
        _uiState.update {
            it.copy(
                currentPath = normalizedContent,
                panePath = normalizedPane,
                parentPath = parent,
                canNavigateUp = parent != null,
                paneDirectories = paneDirectories,
                paneFiles = paneFiles,
                contentDirectories = contentDirectories,
                contentFiles = contentFiles,
                selectedFolderPath = selectedFolderPath?.let(browser::normalizePath),
                loadingFolderPath = null,
                isLoading = false,
                isRefreshing = false
            )
        }
        watching.syncLocalWatch(normalizedContent, normalizedPane)
    }

    fun navigateUp() {
        val state = _uiState.value
        val parent = state.parentPath ?: return
        val pane = browser.normalizePath(state.panePath.ifBlank { browseRoot })
        val content = browser.normalizePath(state.currentPath)

        if (state.selectedFolderPath == null && content == pane) {
            openPath(parent)
            return
        }

        if (browser.normalizePath(parent) == pane) {
            openPath(pane)
            return
        }

        val newContent = browser.resolveWithinRoot(parent)
        val localOnly = target is BrowseTarget.Local
        _uiState.update {
            it.copy(
                isLoading = !localOnly,
                loadingFolderPath = if (localOnly) null else newContent,
                errorMessage = null
            )
        }
        launchBrowse {
            browseWithPinRetry {
                val newPane = browser.parentWithinRoot(newContent) ?: browseRoot
                val (paneListing, contentListing) = coroutineScope {
                    val paneDeferred = async { browser.listAt(newPane) }
                    val contentDeferred = async { browser.listAt(newContent) }
                    paneDeferred.await() to contentDeferred.await()
                }
                applyPaneAndContent(
                    panePath = newPane,
                    contentPath = newContent,
                    paneDirectories = paneListing.directories,
                    paneFiles = paneListing.files,
                    contentDirectories = contentListing.directories,
                    contentFiles = contentListing.files,
                    selectedFolderPath = newContent
                )
            }
        }
    }

    /**
     * System/back gesture: exit selection, climb one folder inside the root, otherwise leave explorer.
     * @return true if consumed by in-explorer navigation, false if caller should exit to devices.
     */
    fun handleBackNavigation(): Boolean {
        if (_uiState.value.isSelectionMode) {
            exitSelectionMode()
            return true
        }
        if (_uiState.value.canNavigateUp) {
            navigateUp()
            return true
        }
        return false
    }

    fun toggleViewMode(includeSplit: Boolean = true) {
        settings.setExplorerViewMode(_uiState.value.viewMode.next(includeSplit))
    }

    fun refresh() {
        reloadListing(showRefreshing = true)
    }

    private var screenShownBefore = false

    /**
     * The view model outlives the screen, so reopening a device would otherwise show the folder as it
     * was last seen. The first showing already loads fresh data; every later one reloads it.
     */
    fun onScreenShown() {
        if (!screenShownBefore) {
            screenShownBefore = true
            return
        }
        if (isRemote) refresh()
    }

    private fun reloadListing(showRefreshing: Boolean) {
        val state = _uiState.value
        val contentPath = state.currentPath.ifBlank { browseRoot }
        val panePath = state.panePath.ifBlank { contentPath }
        val selected = state.selectedFolderPath
        if (showRefreshing) {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
        }
        launchBrowse {
            browseWithPinRetry {
                val paneResolved = browser.resolveWithinRoot(panePath)
                val contentResolved = browser.resolveWithinRoot(contentPath)
                val sameFolder = selected == null ||
                    browser.normalizePath(contentResolved) == browser.normalizePath(paneResolved)
                if (sameFolder) {
                    val listing = browser.listAt(paneResolved, forceRefresh = true)
                    applyPaneAndContent(
                        panePath = paneResolved,
                        contentPath = paneResolved,
                        paneDirectories = listing.directories,
                        paneFiles = listing.files,
                        contentDirectories = listing.directories,
                        contentFiles = listing.files,
                        selectedFolderPath = null
                    )
                } else {
                    val (paneListing, contentListing) = coroutineScope {
                        val paneDeferred = async { browser.listAt(paneResolved, forceRefresh = true) }
                        val contentDeferred = async { browser.listAt(contentResolved, forceRefresh = true) }
                        paneDeferred.await() to contentDeferred.await()
                    }
                    applyPaneAndContent(
                        panePath = paneResolved,
                        contentPath = contentResolved,
                        paneDirectories = paneListing.directories,
                        paneFiles = paneListing.files,
                        contentDirectories = contentListing.directories,
                        contentFiles = contentListing.files,
                        selectedFolderPath = selected
                    )
                }
            }
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null, lastDownloadedPaths = emptyList()) }
    }

    override fun onCleared() {
        browser.close()
        watching.localWatchDebounce?.cancel()
        watching.contentWatch?.close()
        watching.paneWatch?.close()
        super.onCleared()
    }

    fun downloadPreview() = previewActions.downloadPreview()
    fun dismissPreview() = previewActions.dismissPreview()
    fun renameItem(item: RemoteFileItem, newName: String) = mutations.renameItem(item, newName)
    fun compressItem(item: RemoteFileItem) = mutations.compressItem(item)
    fun uncompressItem(item: RemoteFileItem) = mutations.uncompressItem(item)
    fun moveItem(item: RemoteFileItem, destination: String) = mutations.moveItem(item, destination)
    fun deleteItem(item: RemoteFileItem) = mutations.deleteItem(item)
    fun importDropped(paths: List<String>, destinationDirectory: String? = null) = mutations.importDropped(paths, destinationDirectory)
    fun clearPendingCopy() = copying.clearPendingCopy()
    fun copySelected() = copying.copySelected()
    fun copyItem(item: RemoteFileItem) = copying.copyItem(item)
    fun onMultiCopyFabClick() = copying.onMultiCopyFabClick()
    fun sendItemToDevices(item: RemoteFileItem) = copying.sendItemToDevices(item)
    fun acknowledgeMultiCopyIntro() = copying.acknowledgeMultiCopyIntro()
    fun dismissMultiCopyIntro() = copying.dismissMultiCopyIntro()
    fun hideMultiCopyPicker() = copying.hideMultiCopyPicker()
    fun dismissMultiCopyPicker() = copying.dismissMultiCopyPicker()
    fun toggleMultiCopyDevice(deviceId: String) = copying.toggleMultiCopyDevice(deviceId)
    fun confirmMultiCopy() = copying.confirmMultiCopy()
    fun downloadItem(item: RemoteFileItem) = copying.downloadItem(item)
    fun downloadSelected() = copying.downloadSelected()
    fun pasteHere() = copying.pasteHere()
    fun onFileClick(item: RemoteFileItem) = selection.onFileClick(item)
    fun onFileLongClick(item: RemoteFileItem) = selection.onFileLongClick(item)
    fun selectFileExclusive(item: RemoteFileItem) = selection.selectFileExclusive(item)
    fun toggleFileSelectionDesktop(item: RemoteFileItem) = selection.toggleFileSelectionDesktop(item)
    fun extendFileSelection(item: RemoteFileItem) = selection.extendFileSelection(item)
    fun activateFile(item: RemoteFileItem) = selection.activateFile(item)
    fun enterSelectionMode(preselect: RemoteFileItem? = null) = selection.enterSelectionMode(preselect)
    fun exitSelectionMode() = selection.exitSelectionMode()
    fun toggleFileSelection(item: RemoteFileItem) = selection.toggleFileSelection(item)
    fun selectedItems(): List<RemoteFileItem> = selection.selectedItems()
}

/** Thrown when a PIN-protected peer needs (re)unlock before folder navigation. */
class PinSessionRequiredException : Exception(AppI18n.t("pin_required_open_device"))
