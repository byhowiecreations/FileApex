package com.fileapex.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.domain.browse.BrowseListing
import com.fileapex.domain.presence.isTailscaleEnabled
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
    val remoteSleeping: Boolean = false,
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
    /** Anchor for desktop Shift-click range selection. */
    private var selectionAnchorId: String? = null
    private var browseJob: Job? = null
    private var browseGeneration = 0
    private var localWatchDebounce: Job? = null
    private var contentWatch: DirectoryWatch? = null
    private var paneWatch: DirectoryWatch? = null
    private var watchedContent: String? = null
    private var watchedPane: String? = null

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

    init {
        openPath(browseRoot)
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
            runCatching {
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
        syncLocalWatch(normalizedContent, normalizedPane)
    }

    fun onFileClick(item: RemoteFileItem) {
        if (_uiState.value.isSelectionMode) {
            toggleFileSelection(item)
            return
        }
        activateFile(item)
    }

    fun onFileLongClick(item: RemoteFileItem) {
        enterSelectionMode(preselect = item)
    }

    /** Desktop: plain click replaces selection with this file or folder. */
    fun selectFileExclusive(item: RemoteFileItem) {
        selectionAnchorId = item.id
        enterSelectionMode(preselect = item)
    }

    /** Desktop: ⌘/Ctrl-click toggles membership in the selection. */
    fun toggleFileSelectionDesktop(item: RemoteFileItem) {
        if (!_uiState.value.isSelectionMode) {
            selectionAnchorId = item.id
            enterSelectionMode(preselect = item)
            return
        }
        toggleFileSelection(item)
        val selected = _uiState.value.selectedFileIds
        if (item.id in selected) {
            selectionAnchorId = item.id
        }
        if (selected.isEmpty()) {
            exitSelectionMode()
        }
    }

    /** Desktop: Shift-click selects a contiguous range from the anchor. */
    fun extendFileSelection(item: RemoteFileItem) {
        val items = _uiState.value.contentDirectories + _uiState.value.contentFiles
        val anchorId = selectionAnchorId
        val anchorIndex = anchorId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
        val targetIndex = items.indexOfFirst { it.id == item.id }
        if (anchorIndex < 0 || targetIndex < 0) {
            selectFileExclusive(item)
            return
        }
        val from = minOf(anchorIndex, targetIndex)
        val to = maxOf(anchorIndex, targetIndex)
        val rangeIds = items.subList(from, to + 1).map { it.id }.toSet()
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedFileIds = rangeIds,
                canDownloadSelection = isRemote && rangeIds.isNotEmpty(),
                canPaste = false,
                statusMessage = null
            )
        }
    }

    /** Open / preview a file (Android tap outside selection; desktop double-click). */
    fun activateFile(item: RemoteFileItem) {
        when {
            item.isDirectory -> onDirectoryClick(item)
            preview.isImageFile(item) -> openImagePreview(item)
            preview.isTextFile(item) -> openTextPreview(item)
            else -> {
                if (target is BrowseTarget.Local) {
                    com.fileapex.platform.openLocalFile(item.absolutePath, item.name)
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "${item.name} · ${preview.formatBytes(item.sizeBytes)}")
                    }
                }
            }
        }
    }

    fun enterSelectionMode(preselect: RemoteFileItem? = null) {
        val selected = if (preselect != null) setOf(preselect.id) else emptySet()
        if (preselect != null) {
            selectionAnchorId = preselect.id
        }
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedFileIds = selected,
                canDownloadSelection = isRemote && selected.isNotEmpty(),
                canPaste = false,
                statusMessage = null
            )
        }
    }

    fun exitSelectionMode() {
        selectionAnchorId = null
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedFileIds = emptySet(),
                canDownloadSelection = false,
                canPaste = TransferClipboard.hasContent()
            )
        }
    }

    fun toggleFileSelection(item: RemoteFileItem) {
        _uiState.update { state ->
            val next = if (item.id in state.selectedFileIds) {
                state.selectedFileIds - item.id
            } else {
                state.selectedFileIds + item.id
            }
            state.copy(
                selectedFileIds = next,
                canDownloadSelection = isRemote && next.isNotEmpty()
            )
        }
    }

    fun selectedItems(): List<RemoteFileItem> {
        val ids = _uiState.value.selectedFileIds
        val all = _uiState.value.contentDirectories + _uiState.value.contentFiles +
            _uiState.value.paneDirectories + _uiState.value.paneFiles
        return all.distinctBy { it.id }.filter { it.id in ids }
    }

    private fun openImagePreview(item: RemoteFileItem) {
        if (target is BrowseTarget.Local) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        previewItem = item,
                        previewText = null,
                        previewImage = null,
                        isPreviewLoading = true,
                        canDownloadPreview = false,
                        statusMessage = null,
                        errorMessage = null
                    )
                }
                val bitmap = withContext(Dispatchers.IO) {
                    decodeLocalImageFile(item.absolutePath, previewMaxEdgePx().coerceAtMost(1600))
                }
                if (bitmap == null) {
                    _uiState.update {
                        it.copy(
                            previewItem = null,
                            previewImage = null,
                            isPreviewLoading = false,
                            errorMessage = AppI18n.t("preview_failed")
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(previewImage = bitmap, isPreviewLoading = false)
                    }
                }
            }
            return
        }
        runCatching {
            preview.assertPreviewAllowed(item, FilePreviewManager.MAX_PREVIEW_BYTES)
        }.onFailure { error ->
            _uiState.update {
                it.copy(errorMessage = UserFacingErrors.message(error, "preview_failed"))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    previewItem = item,
                    previewText = null,
                    previewImage = null,
                    isPreviewLoading = true,
                    canDownloadPreview = isRemote,
                    statusMessage = null,
                    errorMessage = null
                )
            }
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    preview.loadPreviewBytes(item, FilePreviewManager.MAX_PREVIEW_BYTES)
                }
                withContext(Dispatchers.Default) { decodeImageBytes(bytes, maxEdge = previewMaxEdgePx().coerceAtMost(1600)) }
                    ?: error("Unable to decode image")
            }.fold(
                onSuccess = { bitmap ->
                    _uiState.update {
                        it.copy(
                            previewImage = bitmap,
                            isPreviewLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            previewItem = null,
                            previewImage = null,
                            isPreviewLoading = false,
                            canDownloadPreview = false,
                            errorMessage = UserFacingErrors.message(error, "preview_failed")
                        )
                    }
                }
            )
        }
    }

    private fun openTextPreview(item: RemoteFileItem) {
        runCatching {
            preview.assertPreviewAllowed(item, FilePreviewManager.MAX_TEXT_PREVIEW_BYTES)
        }.onFailure { error ->
            _uiState.update {
                it.copy(errorMessage = UserFacingErrors.message(error, "text_preview_too_large"))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    previewItem = item,
                    previewImage = null,
                    previewText = null,
                    isPreviewLoading = true,
                    canDownloadPreview = isRemote,
                    statusMessage = null,
                    errorMessage = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    preview.loadPreviewBytes(item, FilePreviewManager.MAX_TEXT_PREVIEW_BYTES)
                        .decodeToString()
                        .take(12_000)
                }
            }.fold(
                onSuccess = { text ->
                    _uiState.update {
                        it.copy(
                            previewText = text,
                            isPreviewLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            previewItem = null,
                            isPreviewLoading = false,
                            canDownloadPreview = false,
                            errorMessage = UserFacingErrors.message(error, "preview_failed")
                        )
                    }
                }
            )
        }
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
                val paneListing = browser.listAt(newPane)
                val contentListing = browser.listAt(newContent)
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

    fun renameItem(item: RemoteFileItem, newName: String) {
        if (target !is BrowseTarget.Local) return
        viewModelScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                runCatching { renameLocalEntry(item.absolutePath, newName) }
            }
            renamed.onSuccess { refresh() }
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
            zipped.onSuccess { refresh() }
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
            unpacked.onSuccess { refresh() }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "unable_to_open_folder")) }
                }
        }
    }

    fun deleteItem(item: RemoteFileItem) {
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
                if (finished) refresh()
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
                        runCatching { dropOntoLocal(payload, destination) }.exceptionOrNull()
                    }
                    is BrowseTarget.Remote -> {
                        val localPaths = paths.mapNotNull { localPathOfDrop(it) }
                        if (localPaths.isEmpty()) emptyList()
                        else runCatching {
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
        val endpoint = if (isTailscaleEnabled()) FileApexServices.presenceMonitor.resolveOutboundEndpoint(device) else null
        FileApexServices.transferService.downloadRemoteToDownloads(
            host = endpoint?.host ?: device.lastKnownIp,
            port = endpoint?.port ?: device.port,
            items = listOf(
                RemoteFileItem(
                    id = parsed.path,
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
        if (!payload.startsWith("fileapex-transfer://")) {
            val fileName = payload.substringAfterLast('/')
            return DropPayload(payload, fileName, 0L, null)
        }
        val withoutScheme = payload.removePrefix("fileapex-transfer://")
        val sourceId = withoutScheme.substringBefore('/')
        val rest = withoutScheme.substringAfter('/', "")
        val rawPath = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        val name = query.substringAfter("name=", "").substringBefore('&').ifBlank {
            rawPath.substringAfterLast('/')
        }
        val size = query.substringAfter("size=", "0").substringBefore('&').toLongOrNull() ?: 0L
        return if (sourceId == "local") {
            DropPayload(rawPath, name, size, null)
        } else {
            DropPayload(rawPath, name, size, sourceId)
        }
    }

    private fun localPathOfDrop(payload: String): String? {
        val parsed = parseDropPayload(payload)
        return if (parsed.remoteDeviceId == null) parsed.path else null
    }

    fun toggleViewMode() {
        settings.setExplorerViewMode(_uiState.value.viewMode.cycled())
    }

    fun refresh() {
        reloadListing(showRefreshing = true)
    }

    fun clearPendingCopy() {
        TransferClipboard.clear()
        _uiState.update { it.copy(canPaste = false, clipboardLabel = null) }
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

    fun copySelected() {
        val items = selectedItems()
        runCatching {
            val message = transfers.copySelected(items)
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFileIds = emptySet(),
                    canDownloadSelection = false,
                    canPaste = true,
                    clipboardLabel = TransferClipboard.label(),
                    statusMessage = message
                )
            }
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "copy_failed")) }
        }
    }

    fun copyItem(item: RemoteFileItem) {
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems()
        } else {
            listOf(item)
        }
        runCatching {
            val message = transfers.copySelected(items)
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFileIds = emptySet(),
                    canDownloadSelection = false,
                    canPaste = true,
                    clipboardLabel = TransferClipboard.label(),
                    statusMessage = message
                )
            }
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "copy_failed")) }
        }
    }

    fun onMultiCopyFabClick() {
        if (selectedItems().isEmpty()) {
            _uiState.update { it.copy(errorMessage = ExplorerActionCopy.ERROR_SELECT_FILES) }
            return
        }
        if (!settings.multiCopyIntroAcknowledged.value) {
            _uiState.update { it.copy(showMultiCopyIntro = true) }
            return
        }
        openMultiCopyPicker()
    }

    fun sendItemToDevices(item: RemoteFileItem) {
        if (item.id !in _uiState.value.selectedFileIds) {
            _uiState.update {
                it.copy(
                    isSelectionMode = true,
                    selectedFileIds = setOf(item.id)
                )
            }
        }
        if (!settings.multiCopyIntroAcknowledged.value) {
            _uiState.update { it.copy(showMultiCopyIntro = true) }
            return
        }
        openMultiCopyPicker()
    }

    fun acknowledgeMultiCopyIntro() {
        settings.setMultiCopyIntroAcknowledged(true)
        _uiState.update { it.copy(showMultiCopyIntro = false) }
        openMultiCopyPicker()
    }

    fun dismissMultiCopyIntro() {
        _uiState.update { it.copy(showMultiCopyIntro = false) }
    }

    /** Closes the picker and leaves a running send alone; it reports through the status message. */
    fun hideMultiCopyPicker() {
        _uiState.update { it.copy(showMultiCopyPicker = false) }
    }

    fun dismissMultiCopyPicker() {
        _uiState.update {
            it.copy(
                showMultiCopyPicker = false,
                multiCopyOptions = emptyList(),
                selectedMultiCopyDeviceIds = emptySet()
            )
        }
    }

    fun toggleMultiCopyDevice(deviceId: String) {
        _uiState.update { state ->
            val next = if (deviceId in state.selectedMultiCopyDeviceIds) {
                state.selectedMultiCopyDeviceIds - deviceId
            } else {
                state.selectedMultiCopyDeviceIds + deviceId
            }
            state.copy(selectedMultiCopyDeviceIds = next)
        }
    }

    fun confirmMultiCopy() {
        val items = selectedItems()
        if (items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = ExplorerActionCopy.ERROR_SELECT_FILES) }
            return
        }
        val selectedIds = _uiState.value.selectedMultiCopyDeviceIds
        val selectedDevices = _uiState.value.multiCopyOptions.filter { it.deviceId in selectedIds }
        if (selectedDevices.isEmpty()) {
            _uiState.update { it.copy(errorMessage = AppI18n.t("select_destination_device")) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isMultiCopying = true, errorMessage = null) }
            runCatching {
                val sources = transfers.sourcesFrom(items)
                transfers.sendOrQueue(sources, selectedDevices)
            }.fold(
                onSuccess = { outcome ->
                    val batch = outcome.batch
                    val failCount = batch?.results?.sumOf { it.failures.size } ?: 0
                    val message = when {
                        outcome.hadQueue && (batch == null || failCount == 0) -> outcome.message
                        failCount == 0 && batch != null -> {
                            val deviceLabel = if (selectedDevices.size == 1) {
                                selectedDevices.first().deviceName
                            } else {
                                AppI18n.plural("device_count", selectedDevices.size)
                            }
                            if (items.size == 1) {
                                AppI18n.t("file_sent_to", deviceLabel)
                            } else {
                                AppI18n.t("files_sent_to", deviceLabel)
                            }.let { base ->
                                if (outcome.hadQueue) "$base ${outcome.message}" else base
                            }

                        }
                        batch?.allFailed == true && !outcome.hadQueue -> ExplorerActionCopy.ERROR_SEND_FAILED
                        else -> outcome.message.ifBlank {
                            ExplorerActionCopy.sendFinishedWithErrors(failCount)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isMultiCopying = false,
                            showMultiCopyPicker = false,
                            multiCopyOptions = emptyList(),
                            selectedMultiCopyDeviceIds = emptySet(),
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                            canDownloadSelection = false,
                            canPaste = TransferClipboard.hasContent(),
                            statusMessage = message,
                            errorMessage = batch?.results
                                ?.flatMap { it.failures.values }
                                ?.firstOrNull()
                                ?.takeIf { batch.allFailed && !outcome.hadQueue }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isMultiCopying = false,
                            errorMessage = error.message ?: ExplorerActionCopy.ERROR_SEND_FAILED
                        )
                    }
                }
            )
        }
    }

    private fun openMultiCopyPicker() {
        _uiState.update {
            it.copy(
                showMultiCopyPicker = true,
                selectedMultiCopyDeviceIds = emptySet()
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val options = transfers.buildMultiCopyOptions()
            _uiState.update {
                it.copy(multiCopyOptions = options)
            }
        }
    }

    fun downloadItem(item: RemoteFileItem) {
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems()
        } else {
            listOf(item)
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            runCatching {
                transfers.downloadRemote(items)
            }.fold(
                onSuccess = { paths ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                            canDownloadSelection = false,
                            canPaste = TransferClipboard.hasContent(),
                            statusMessage = if (paths.size == 1) {
                                AppI18n.t("downloaded_to", paths.first())
                            } else {
                                AppI18n.t("downloaded_files_to", paths.size, DownloadsPaths.displayLabel())
                            },
                            lastDownloadedPaths = paths
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = UserFacingErrors.message(error, "download_failed")
                        )
                    }
                }
            )
        }
    }

    fun downloadSelected() {
        val items = selectedItems()
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            runCatching {
                transfers.downloadRemote(items)
            }.fold(
                onSuccess = { paths ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                            canDownloadSelection = false,
                            canPaste = TransferClipboard.hasContent(),
                            statusMessage = if (paths.size == 1) {
                                AppI18n.t("downloaded_to", paths.first())
                            } else {
                                AppI18n.t("downloaded_files_to", paths.size, DownloadsPaths.displayLabel())
                            },
                            lastDownloadedPaths = paths
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = UserFacingErrors.message(error, "download_failed")
                        )
                    }
                }
            )
        }
    }

    fun downloadPreview() {
        val item = _uiState.value.previewItem ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            runCatching {
                transfers.downloadRemote(listOf(item))
            }.fold(
                onSuccess = { paths ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            statusMessage = AppI18n.t("downloaded_to", paths.first()),
                            lastDownloadedPaths = paths
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = UserFacingErrors.message(error, "download_failed")
                        )
                    }
                }
            )
        }
    }

    fun pasteHere() {
        viewModelScope.launch {
            runCatching {
                val paths = transfers.pasteInto(_uiState.value.currentPath)
                _uiState.update {
                    it.copy(
                        statusMessage = if (paths.size == 1) {
                            AppI18n.t("pasted_to", paths.first())
                        } else {
                            AppI18n.t("pasted_files", paths.size)
                        }
                    )
                }
                refresh()
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "paste_failed")) }
            }
        }
    }

    fun dismissPreview() {
        _uiState.update {
            it.copy(
                previewItem = null,
                previewText = null,
                previewImage = null,
                isPreviewLoading = false,
                canDownloadPreview = false
            )
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null, lastDownloadedPaths = emptyList()) }
    }

    private fun syncLocalWatch(contentPath: String, panePath: String) {
        if (target !is BrowseTarget.Local) return
        if (contentPath == watchedContent && panePath == watchedPane) return
        contentWatch?.close()
        paneWatch?.close()
        contentWatch = null
        paneWatch = null
        watchedContent = contentPath
        watchedPane = panePath
        contentWatch = openLocalWatch(contentPath)
        if (panePath != contentPath) {
            paneWatch = openLocalWatch(panePath)
        }
    }

    private fun openLocalWatch(path: String): DirectoryWatch? {
        if (path.isBlank()) return null
        return runCatching { watchLocalDirectory(path, ::onLocalFolderChanged) }.getOrNull()
    }

    private fun onLocalFolderChanged() {
        val generation = browseGeneration
        localWatchDebounce?.cancel()
        localWatchDebounce = viewModelScope.launch {
            delay(400)
            if (generation != browseGeneration) return@launch
            if (target !is BrowseTarget.Local) return@launch
            reloadListing(showRefreshing = false)
        }
    }

    override fun onCleared() {
        localWatchDebounce?.cancel()
        contentWatch?.close()
        paneWatch?.close()
        super.onCleared()
    }
}

/** Thrown when a PIN-protected peer needs (re)unlock before folder navigation. */
class PinSessionRequiredException : Exception(AppI18n.t("pin_required_open_device"))
