package com.omninode.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.data.clipboard.TransferClipboard
import com.omninode.data.identity.LocalIdentity
import com.omninode.di.OmniNodeServices
import com.omninode.domain.model.RemoteFileItem
import com.omninode.domain.transfer.MultiCopyDeviceOption
import com.omninode.domain.transfer.MultiCopySource
import com.omninode.platform.decodeImageBytes
import com.omninode.platform.defaultDownloadsDir
import com.omninode.platform.localIpv4Addresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

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
    val isMultiCopying: Boolean = false
)

class ExplorerViewModel(
    private val target: BrowseTarget
) : ViewModel() {
    private val transfer = OmniNodeServices.transferService
    private val identity: LocalIdentity
        get() = OmniNodeServices.localIdentity
    private val settings = OmniNodeServices.settings
    private val browseRoot: String = normalizePath(target.rootPath)
    private val isRemote: Boolean = target is BrowseTarget.Remote

    private val _uiState = MutableStateFlow(
        ExplorerUiState(
            deviceTitle = target.displayName,
            clipboardLabel = TransferClipboard.label(),
            canPaste = TransferClipboard.hasContent(),
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
        val resolved = resolveWithinRoot(path)
        viewModelScope.launch {
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
            runCatching {
                val (dirs, files) = listAt(resolved)
                applyPaneAndContent(
                    panePath = resolved,
                    contentPath = resolved,
                    paneDirectories = dirs,
                    contentDirectories = dirs,
                    contentFiles = files,
                    selectedFolderPath = null
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Unable to open folder"
                    )
                }
            }
        }
    }

    /**
     * Wide left-pane folder: keep sibling list, show that folder's full contents on the right.
     */
    fun onPaneFolderClick(item: RemoteFileItem) {
        if (_uiState.value.isSelectionMode) return
        if (!isWithinRoot(item.absolutePath)) {
            _uiState.update {
                it.copy(statusMessage = "That folder is outside this device's browsable root")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val resolved = resolveWithinRoot(item.absolutePath)
                val (dirs, files) = listAt(resolved)
                applyPaneAndContent(
                    panePath = _uiState.value.panePath.ifBlank { browseRoot },
                    contentPath = resolved,
                    paneDirectories = _uiState.value.paneDirectories,
                    contentDirectories = dirs,
                    contentFiles = files,
                    selectedFolderPath = resolved
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to open folder"
                    )
                }
            }
        }
    }

    /**
     * Folder inside the content pane (or phone list): drill in; left becomes this folder's parent.
     */
    fun onContentDirectoryClick(item: RemoteFileItem) {
        if (_uiState.value.isSelectionMode) return
        if (!isWithinRoot(item.absolutePath)) {
            _uiState.update {
                it.copy(statusMessage = "That folder is outside this device's browsable root")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val newContent = resolveWithinRoot(item.absolutePath)
                val newPane = parentWithinRoot(newContent) ?: browseRoot
                val (paneDirs, _) = listAt(newPane)
                val (contentDirs, contentFiles) = listAt(newContent)
                applyPaneAndContent(
                    panePath = newPane,
                    contentPath = newContent,
                    paneDirectories = paneDirs,
                    contentDirectories = contentDirs,
                    contentFiles = contentFiles,
                    selectedFolderPath = newContent
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to open folder"
                    )
                }
            }
        }
    }

    fun onDirectoryClick(item: RemoteFileItem) {
        // Phone / single-column: full navigation into the folder.
        onContentDirectoryClick(item)
    }

    private suspend fun listAt(path: String): Pair<List<RemoteFileItem>, List<RemoteFileItem>> {
        return when (target) {
            is BrowseTarget.Local -> {
                val listing = transfer.listLocal(path)
                listing.directories to listing.files
            }
            is BrowseTarget.Remote -> {
                val items = transfer.listRemote(target.host, target.port, path)
                val directories = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                val files = items.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
                directories to files
            }
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
        val normalizedContent = normalizePath(contentPath)
        val normalizedPane = normalizePath(panePath)
        val parent = parentWithinRoot(normalizedContent)
        _uiState.update {
            it.copy(
                currentPath = normalizedContent,
                panePath = normalizedPane,
                parentPath = parent,
                canNavigateUp = parent != null,
                paneDirectories = paneDirectories,
                contentDirectories = contentDirectories,
                contentFiles = contentFiles,
                selectedFolderPath = selectedFolderPath?.let(::normalizePath),
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
        when {
            isImageFile(item) -> openImagePreview(item)
            isTextFile(item) -> openTextPreview(item)
            else -> {
                _uiState.update {
                    it.copy(statusMessage = "${item.name} · ${formatBytes(item.sizeBytes)}")
                }
            }
        }
    }

    fun onFileLongClick(item: RemoteFileItem) {
        enterSelectionMode(preselect = item)
    }

    fun enterSelectionMode(preselect: RemoteFileItem? = null) {
        val selected = if (preselect != null) setOf(preselect.id) else emptySet()
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
        if (item.sizeBytes > MAX_PREVIEW_BYTES) {
            _uiState.update {
                it.copy(errorMessage = "Image is too large to preview (>${MAX_PREVIEW_BYTES / (1024 * 1024)} MB)")
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
                val bytes = withContext(Dispatchers.IO) { loadFileBytes(item) }
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
        if (item.sizeBytes > MAX_TEXT_PREVIEW_BYTES) {
            _uiState.update {
                it.copy(errorMessage = "Text file is too large to preview")
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
                    loadFileBytes(item).decodeToString().take(12_000)
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

    private suspend fun loadFileBytes(item: RemoteFileItem): ByteArray {
        return when (target) {
            is BrowseTarget.Local -> {
                SystemFileSystem.source(Path(item.absolutePath)).buffered().use { it.readByteArray() }
            }
            is BrowseTarget.Remote -> {
                OmniNodeServices.client.downloadBytes(
                    host = target.host,
                    port = target.port,
                    remotePath = item.absolutePath,
                    maxBytes = MAX_PREVIEW_BYTES
                )
            }
        }
    }

    private fun isImageFile(item: RemoteFileItem): Boolean {
        val name = item.name.lowercase()
        return item.mimeType.startsWith("image/") ||
            name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp") ||
            name.endsWith(".gif") ||
            name.endsWith(".bmp")
    }

    private fun isTextFile(item: RemoteFileItem): Boolean {
        val name = item.name.lowercase()
        return item.mimeType.startsWith("text/") ||
            name.endsWith(".txt") ||
            name.endsWith(".md") ||
            name.endsWith(".log") ||
            name.endsWith(".json") ||
            name.endsWith(".csv")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${(kb * 10).toInt() / 10.0} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${(mb * 10).toInt() / 10.0} MB"
        val gb = mb / 1024.0
        return "${(gb * 10).toInt() / 10.0} GB"
    }

    fun navigateUp() {
        val state = _uiState.value
        val parent = state.parentPath ?: return
        val pane = normalizePath(state.panePath.ifBlank { browseRoot })
        val content = normalizePath(state.currentPath)

        // At pane root view (no left selection): move both panes up.
        if (state.selectedFolderPath == null && content == pane) {
            openPath(parent)
            return
        }

        // Selected/drilled folder whose parent is the left pane → return to pane file list.
        if (normalizePath(parent) == pane) {
            openPath(pane)
            return
        }

        // Deeper drill: show parent folder contents; left lists that parent's siblings.
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val newContent = resolveWithinRoot(parent)
                val newPane = parentWithinRoot(newContent) ?: browseRoot
                val (paneDirs, _) = listAt(newPane)
                val (contentDirs, contentFiles) = listAt(newContent)
                applyPaneAndContent(
                    panePath = newPane,
                    contentPath = newContent,
                    paneDirectories = paneDirs,
                    contentDirectories = contentDirs,
                    contentFiles = contentFiles,
                    selectedFolderPath = newContent
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to navigate up"
                    )
                }
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
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching {
                val (paneDirs, paneFiles) = listAt(resolveWithinRoot(panePath))
                if (selected == null || normalizePath(contentPath) == normalizePath(panePath)) {
                    applyPaneAndContent(
                        panePath = resolveWithinRoot(panePath),
                        contentPath = resolveWithinRoot(panePath),
                        paneDirectories = paneDirs,
                        contentDirectories = paneDirs,
                        contentFiles = paneFiles,
                        selectedFolderPath = null
                    )
                } else {
                    val (contentDirs, contentFiles) = listAt(resolveWithinRoot(contentPath))
                    applyPaneAndContent(
                        panePath = resolveWithinRoot(panePath),
                        contentPath = resolveWithinRoot(contentPath),
                        paneDirectories = paneDirs,
                        contentDirectories = contentDirs,
                        contentFiles = contentFiles,
                        selectedFolderPath = selected
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error.message ?: "Unable to refresh folder"
                    )
                }
            }
        }
    }

    fun copySelected() {
        val items = selectedFiles()
        if (items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one file to copy") }
            return
        }
        runCatching {
            when (target) {
                is BrowseTarget.Local -> {
                    val host = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"
                    transfer.copyLocalFiles(identity, items, host)
                }
                is BrowseTarget.Remote -> {
                    transfer.copyRemoteFiles(
                        sourceDeviceId = target.deviceId,
                        sourceDeviceName = target.displayName,
                        host = target.host,
                        port = target.port,
                        items = items
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFileIds = emptySet(),
                    canDownloadSelection = false,
                    canPaste = true,
                    clipboardLabel = TransferClipboard.label(),
                    statusMessage = if (items.size == 1) {
                        "Copied ${items.first().name} — open a folder and tap Paste here"
                    } else {
                        "Copied ${items.size} files — open a folder and tap Paste here"
                    }
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
        val sources = items.map { item ->
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
        viewModelScope.launch {
            _uiState.update { it.copy(isMultiCopying = true, errorMessage = null) }
            runCatching {
                transfer.multiCopyToDevices(sources, selectedDevices)
            }.fold(
                onSuccess = { results ->
                    val okDevices = results.flatMap { it.succeededDeviceIds }.toSet()
                    val failCount = results.sumOf { it.failures.size }
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
                        okDevices.isEmpty() -> "Multi Copy failed for all destinations"
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
                            errorMessage = results
                                .flatMap { it.failures.values }
                                .firstOrNull()
                                ?.takeIf { failCount > 0 && okDevices.isEmpty() }
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
            val options = buildMultiCopyOptions()
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

    private suspend fun buildMultiCopyOptions(): List<MultiCopyDeviceOption> {
        val onlineIds = OmniNodeServices.presenceMonitor.onlineDeviceIds.value
        val localHost = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        val sourceDeviceId = when (target) {
            is BrowseTarget.Local -> identity.deviceId
            is BrowseTarget.Remote -> target.deviceId
        }
        val options = mutableListOf(
            MultiCopyDeviceOption(
                deviceId = LocalIdentity.LOCAL_DEVICE_ID,
                deviceName = "This device (${identity.deviceName})",
                isLocal = true,
                host = localHost,
                port = identity.sharePort,
                destinationRoot = defaultDownloadsDir()
            )
        )
        val peers = OmniNodeServices.deviceRepository.listDevices()
            .filter { it.deviceId != sourceDeviceId }
            .filter { it.deviceId in onlineIds }
            .sortedBy { it.deviceName.lowercase() }
        for (peer in peers) {
            val downloadsRoot = runCatching {
                val remoteIdentity = OmniNodeServices.client.fetchIdentity(peer.lastKnownIp, peer.port)
                remoteIdentity.downloadsPath.trim().ifBlank {
                    fallbackDownloadsPath(peer.rootPath)
                }
            }.getOrElse {
                fallbackDownloadsPath(peer.rootPath)
            }
            options += MultiCopyDeviceOption(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                isLocal = false,
                host = peer.lastKnownIp,
                port = peer.port,
                destinationRoot = downloadsRoot
            )
        }
        return options
    }

    private fun fallbackDownloadsPath(rootPath: String): String {
        val trimmed = rootPath.trimEnd('/', '\\')
        // Prefer Android-style Download/, then desktop Downloads/.
        return "$trimmed/Download/OmniNode"
    }

    fun downloadSelected() {
        val remote = target as? BrowseTarget.Remote ?: return
        val items = selectedFiles()
        if (items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one file to download") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            runCatching {
                transfer.downloadRemoteToDownloads(remote.host, remote.port, items)
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
        val remote = target as? BrowseTarget.Remote ?: return
        val item = _uiState.value.previewItem ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            runCatching {
                transfer.downloadRemoteToDownloads(remote.host, remote.port, listOf(item))
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
                val paths = when (target) {
                    is BrowseTarget.Local -> transfer.pasteIntoLocal(_uiState.value.currentPath)
                    is BrowseTarget.Remote -> transfer.pasteIntoRemote(
                        host = target.host,
                        port = target.port,
                        targetDirectory = _uiState.value.currentPath
                    )
                }
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

    private fun resolveWithinRoot(path: String): String {
        val normalized = normalizePath(path)
        return if (isWithinRoot(normalized)) normalized else browseRoot
    }

    private fun isWithinRoot(path: String): Boolean {
        val current = normalizePath(path)
        return current == browseRoot || current.startsWith("$browseRoot/")
    }

    private fun parentWithinRoot(path: String): String? {
        val current = normalizePath(path)
        if (current == browseRoot) return null
        val slash = current.lastIndexOf('/')
        if (slash <= 0) return null
        val parent = current.substring(0, slash).ifBlank { "/" }
        val normalizedParent = normalizePath(parent)
        return if (isWithinRoot(normalizedParent)) normalizedParent else null
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return browseRoot
        val trimmed = path.replace('\\', '/').trimEnd('/')
        return trimmed.ifBlank { "/" }
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 25L * 1024L * 1024L
        private const val MAX_TEXT_PREVIEW_BYTES = 1L * 1024L * 1024L
    }
}
