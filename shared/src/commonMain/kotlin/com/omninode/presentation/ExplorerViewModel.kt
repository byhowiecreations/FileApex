package com.omninode.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.data.clipboard.TransferClipboard
import com.omninode.di.OmniNodeServices
import com.omninode.domain.browse.BrowserCoordinator
import com.omninode.domain.model.RemoteFileItem
import com.omninode.domain.preview.FilePreviewManager
import com.omninode.domain.transfer.ExplorerTransferManager
import com.omninode.domain.transfer.MultiCopyDeviceOption
import com.omninode.platform.decodeImageBytes
import com.omninode.session.DeviceSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExplorerUiState(
    val deviceTitle: String = "",
    /** Path whose contents are shown (right pane / phone list). */
    val currentPath: String = "",
    /** Path whose folders appear in the wide left pane. */
    val panePath: String = "",
    /** Parent within the browse root only; null means back should leave the explorer. */
    val parentPath: String? = null,
    val canNavigateUp: Boolean = false,
    /** Folders at [panePath] (wide left column). */
    val paneDirectories: List<RemoteFileItem> = emptyList(),
    /** Folders inside [currentPath] (right pane / phone list). */
    val contentDirectories: List<RemoteFileItem> = emptyList(),
    /** Files inside [currentPath]. */
    val contentFiles: List<RemoteFileItem> = emptyList(),
    /** Highlighted folder on the left; null when right shows files of [panePath]. */
    val selectedFolderPath: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
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
    val pinUnlockError: String? = null
)

class ExplorerViewModel(
    private val target: BrowseTarget
) : ViewModel() {
    private val browser = BrowserCoordinator(target, OmniNodeServices.transferService)
    private val preview = FilePreviewManager(target)
    private val transfers = ExplorerTransferManager(
        target = target,
        transferManager = OmniNodeServices.transferManager,
        identityProvider = { OmniNodeServices.localIdentity }
    )
    private val settings = OmniNodeServices.settings
    private val browseRoot: String = browser.browseRoot
    private val isRemote: Boolean = browser.isRemote
    /** Resume after mid-explorer PIN re-entry. */
    private var pendingBrowseAction: (suspend () -> Unit)? = null
    /** Anchor for desktop Shift-click range selection. */
    private var selectionAnchorId: String? = null
    private var browseJob: Job? = null

    private val _uiState = MutableStateFlow(
        ExplorerUiState(
            deviceTitle = target.displayName,
            clipboardLabel = transfers.clipboardLabel(),
            canPaste = transfers.clipboardHasContent(),
            isRemoteTarget = isRemote
        )
    )
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    init {
        openPath(browseRoot)
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
        launchBrowse {
            browseWithPinRetry {
                _uiState.update {
                    it.copy(
                        isLoading = true,
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
                val listing = browser.listAt(resolved)
                applyPaneAndContent(
                    panePath = resolved,
                    contentPath = resolved,
                    paneDirectories = listing.directories,
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
        if (_uiState.value.isSelectionMode) return
        if (!browser.isWithinRoot(item.absolutePath)) {
            _uiState.update {
                it.copy(statusMessage = "That folder is outside this device's browsable root")
            }
            return
        }
        launchBrowse {
            browseWithPinRetry {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val resolved = browser.resolveWithinRoot(item.absolutePath)
                val listing = browser.listAt(resolved)
                applyPaneAndContent(
                    panePath = _uiState.value.panePath.ifBlank { browseRoot },
                    contentPath = resolved,
                    paneDirectories = _uiState.value.paneDirectories,
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
        if (_uiState.value.isSelectionMode) return
        if (!browser.isWithinRoot(item.absolutePath)) {
            _uiState.update {
                it.copy(statusMessage = "That folder is outside this device's browsable root")
            }
            return
        }
        launchBrowse {
            browseWithPinRetry {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val newContent = browser.resolveWithinRoot(item.absolutePath)
                val newPane = browser.parentWithinRoot(newContent) ?: browseRoot
                val paneListing = browser.listAt(newPane)
                val contentListing = browser.listAt(newContent)
                applyPaneAndContent(
                    panePath = newPane,
                    contentPath = newContent,
                    paneDirectories = paneListing.directories,
                    contentDirectories = contentListing.directories,
                    contentFiles = contentListing.files,
                    selectedFolderPath = newContent
                )
            }
        }
    }

    fun onDirectoryClick(item: RemoteFileItem) {
        onContentDirectoryClick(item)
    }

    private fun launchBrowse(block: suspend () -> Unit) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch { block() }
    }

    private suspend fun browseWithPinRetry(block: suspend () -> Unit) {
        runCatching {
            block()
        }.onFailure { error ->
            if (error is PinSessionRequiredException) {
                requestPinThen { browseWithPinRetry(block) }
                return
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = error.message ?: "Unable to open folder"
                )
            }
        }
    }

    fun cancelPinUnlock() {
        pendingBrowseAction = null
        _uiState.update {
            it.copy(pendingPinUnlock = false, pinUnlockError = null, isLoading = false)
        }
    }

    fun confirmPinUnlock(pin: String) {
        val remote = target as? BrowseTarget.Remote ?: return
        viewModelScope.launch {
            runCatching {
                require(pin.isNotBlank()) { "PIN is required" }
                OmniNodeServices.client.verifyPin(
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
                    it.copy(pinUnlockError = error.message ?: "Incorrect PIN")
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
                isRefreshing = false
            )
        }
    }

    private fun applyPaneAndContent(
        panePath: String,
        contentPath: String,
        paneDirectories: List<RemoteFileItem>,
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
                contentDirectories = contentDirectories,
                contentFiles = contentFiles,
                selectedFolderPath = selectedFolderPath?.let(browser::normalizePath),
                isLoading = false,
                isRefreshing = false
            )
        }
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

    /** Desktop: plain click replaces selection with this file. */
    fun selectFileExclusive(item: RemoteFileItem) {
        if (item.isDirectory) return
        selectionAnchorId = item.id
        enterSelectionMode(preselect = item)
    }

    /** Desktop: ⌘/Ctrl-click toggles membership in the selection. */
    fun toggleFileSelectionDesktop(item: RemoteFileItem) {
        if (item.isDirectory) return
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
        if (item.isDirectory) return
        val files = _uiState.value.contentFiles
        val anchorId = selectionAnchorId
        val anchorIndex = anchorId?.let { id -> files.indexOfFirst { it.id == id } } ?: -1
        val targetIndex = files.indexOfFirst { it.id == item.id }
        if (anchorIndex < 0 || targetIndex < 0) {
            selectFileExclusive(item)
            return
        }
        val from = minOf(anchorIndex, targetIndex)
        val to = maxOf(anchorIndex, targetIndex)
        val rangeIds = files.subList(from, to + 1).map { it.id }.toSet()
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
            preview.isImageFile(item) -> openImagePreview(item)
            preview.isTextFile(item) -> openTextPreview(item)
            else -> {
                _uiState.update {
                    it.copy(statusMessage = "${item.name} · ${preview.formatBytes(item.sizeBytes)}")
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
        if (item.isDirectory) return
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

    private fun selectedFiles(): List<RemoteFileItem> {
        val ids = _uiState.value.selectedFileIds
        return _uiState.value.contentFiles.filter { it.id in ids }
    }

    private fun openImagePreview(item: RemoteFileItem) {
        runCatching {
            preview.assertPreviewAllowed(item, FilePreviewManager.MAX_PREVIEW_BYTES)
        }.onFailure { error ->
            _uiState.update {
                it.copy(errorMessage = error.message ?: "Preview failed")
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
                decodeImageBytes(bytes)
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
                            errorMessage = error.message ?: "Preview failed"
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
                it.copy(errorMessage = error.message ?: "Text file is too large to preview")
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
                            errorMessage = error.message ?: "Preview failed"
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

        launchBrowse {
            browseWithPinRetry {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val newContent = browser.resolveWithinRoot(parent)
                val newPane = browser.parentWithinRoot(newContent) ?: browseRoot
                val paneListing = browser.listAt(newPane)
                val contentListing = browser.listAt(newContent)
                applyPaneAndContent(
                    panePath = newPane,
                    contentPath = newContent,
                    paneDirectories = paneListing.directories,
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

    fun refresh() {
        val state = _uiState.value
        val contentPath = state.currentPath.ifBlank { browseRoot }
        val panePath = state.panePath.ifBlank { contentPath }
        val selected = state.selectedFolderPath
        launchBrowse {
            browseWithPinRetry {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
                val paneListing = browser.listAt(browser.resolveWithinRoot(panePath))
                if (selected == null ||
                    browser.normalizePath(contentPath) == browser.normalizePath(panePath)
                ) {
                    applyPaneAndContent(
                        panePath = browser.resolveWithinRoot(panePath),
                        contentPath = browser.resolveWithinRoot(panePath),
                        paneDirectories = paneListing.directories,
                        contentDirectories = paneListing.directories,
                        contentFiles = paneListing.files,
                        selectedFolderPath = null
                    )
                } else {
                    val contentListing = browser.listAt(browser.resolveWithinRoot(contentPath))
                    applyPaneAndContent(
                        panePath = browser.resolveWithinRoot(panePath),
                        contentPath = browser.resolveWithinRoot(contentPath),
                        paneDirectories = paneListing.directories,
                        contentDirectories = contentListing.directories,
                        contentFiles = contentListing.files,
                        selectedFolderPath = selected
                    )
                }
            }
        }
    }

    fun copySelected() {
        val items = selectedFiles()
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
            _uiState.update { it.copy(errorMessage = error.message ?: "COPY failed") }
        }
    }

    fun onMultiCopyFabClick() {
        if (selectedFiles().isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one file for Multi Copy") }
            return
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
        val items = selectedFiles()
        if (items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one file for Multi Copy") }
            return
        }
        val selectedIds = _uiState.value.selectedMultiCopyDeviceIds
        val selectedDevices = _uiState.value.multiCopyOptions.filter { it.deviceId in selectedIds }
        if (selectedDevices.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one destination device") }
            return
        }
        val sources = transfers.sourcesFrom(items)
        viewModelScope.launch {
            _uiState.update { it.copy(isMultiCopying = true, errorMessage = null) }
            runCatching {
                transfers.sendToDevices(sources, selectedDevices)
            }.fold(
                onSuccess = { batch ->
                    val failCount = batch.results.sumOf { it.failures.size }
                    val message = when {
                        failCount == 0 -> {
                            val deviceLabel = if (selectedDevices.size == 1) {
                                selectedDevices.first().deviceName
                            } else {
                                "${selectedDevices.size} devices"
                            }
                            if (items.size == 1) {
                                "Multi Copied ${items.first().name} to $deviceLabel"
                            } else {
                                "Multi Copied ${items.size} files to $deviceLabel"
                            }
                        }
                        batch.allFailed -> "Multi Copy failed for all destinations"
                        else -> "Multi Copy finished with $failCount error(s)"
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
                            errorMessage = batch.results
                                .flatMap { it.failures.values }
                                .firstOrNull()
                                ?.takeIf { batch.allFailed }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isMultiCopying = false,
                            errorMessage = error.message ?: "Multi Copy failed"
                        )
                    }
                }
            )
        }
    }

    private fun openMultiCopyPicker() {
        viewModelScope.launch {
            val options = transfers.buildMultiCopyOptions()
            if (options.isEmpty()) {
                _uiState.update {
                    it.copy(errorMessage = "No online destination devices available")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    showMultiCopyPicker = true,
                    multiCopyOptions = options,
                    selectedMultiCopyDeviceIds = emptySet()
                )
            }
        }
    }

    fun downloadSelected() {
        val items = selectedFiles()
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
                                "Downloaded to ${paths.first()}"
                            } else {
                                "Downloaded ${paths.size} files to Downloads/OmniNode"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = error.message ?: "Download failed"
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
                            statusMessage = "Downloaded to ${paths.first()}"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = error.message ?: "Download failed"
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
                            "Pasted to ${paths.first()}"
                        } else {
                            "Pasted ${paths.size} files"
                        }
                    )
                }
                refresh()
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message ?: "PASTE failed") }
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
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}

/** Thrown when a PIN-protected peer needs (re)unlock before folder navigation. */
class PinSessionRequiredException : Exception("PIN required to browse this device")
