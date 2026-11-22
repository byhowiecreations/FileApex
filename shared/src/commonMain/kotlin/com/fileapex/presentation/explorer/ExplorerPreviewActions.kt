package com.fileapex.presentation.explorer

import androidx.lifecycle.viewModelScope
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.domain.transfer.ExplorerTransferManager
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.platform.decodeImageBytes
import com.fileapex.platform.decodeLocalImageFile
import com.fileapex.platform.PDF_PREVIEW_MAX_PAGES
import com.fileapex.platform.PDF_PREVIEW_WIDTH_PX
import com.fileapex.platform.canRenderPdf
import com.fileapex.platform.renderPdfPages
import com.fileapex.platform.previewMaxEdgePx
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ExplorerPreviewActions(
    private val _uiState: MutableStateFlow<ExplorerUiState>,
    private val viewModelScope: CoroutineScope,
    private val target: BrowseTarget,
    private val preview: FilePreviewManager,
    private val transfers: ExplorerTransferManager,
    private val isRemote: Boolean
) {
    fun openImagePreview(item: RemoteFileItem) {
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
        if (!preview.previewFits(item, FilePreviewManager.MAX_PREVIEW_BYTES)) {
            openDownloadPrompt(item)
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
            cancellableCatching("ExplorerPreviewActions openImagePreview 2") {
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
                            previewImage = null,
                            isPreviewLoading = false,
                            errorMessage = UserFacingErrors.message(error, "preview_failed")
                        )
                    }
                }
            )
        }
    }

    fun openTextPreview(item: RemoteFileItem) {
        if (!preview.previewFits(item, FilePreviewManager.MAX_TEXT_PREVIEW_BYTES)) {
            openDownloadPrompt(item)
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
            cancellableCatching("ExplorerPreviewActions openTextPreview 2") {
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
                            isPreviewLoading = false,
                            errorMessage = UserFacingErrors.message(error, "preview_failed")
                        )
                    }
                }
            )
        }
    }

    fun openDownloadPrompt(item: RemoteFileItem) {
        _uiState.update {
            it.copy(
                previewItem = item,
                previewText = null,
                previewImage = null,
                isPreviewLoading = false,
                canDownloadPreview = isRemote,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    fun openPdfPreview(item: RemoteFileItem) {
        if (!canRenderPdf() || !preview.previewFits(item, FilePreviewManager.MAX_PREVIEW_BYTES)) {
            openDownloadPrompt(item)
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    previewItem = item,
                    previewText = null,
                    previewImage = null,
                    previewPages = emptyList(),
                    isPreviewLoading = true,
                    canDownloadPreview = isRemote,
                    statusMessage = null,
                    errorMessage = null
                )
            }
            val pages = cancellableCatching("ExplorerPreviewActions openPdfPreview") {
                val bytes = withContext(Dispatchers.IO) {
                    preview.loadPreviewBytes(item, FilePreviewManager.MAX_PREVIEW_BYTES)
                }
                withContext(Dispatchers.Default) {
                    renderPdfPages(bytes, PDF_PREVIEW_WIDTH_PX, PDF_PREVIEW_MAX_PAGES)
                }
            }.getOrDefault(emptyList())
            _uiState.update { it.copy(previewPages = pages, isPreviewLoading = false) }
        }
    }

    fun downloadPreview() {
        val item = _uiState.value.previewItem ?: return
        downloadSingleRemoteItem(item)
    }

    private fun downloadSingleRemoteItem(
        item: RemoteFileItem,
        onDownloadedPath: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null, statusMessage = null) }
            cancellableCatching("ExplorerPreviewActions downloadSingleRemoteItem") {
                transfers.downloadRemote(listOf(item))
            }.fold(
                onSuccess = { paths ->
                    val downloadedPath = paths.firstOrNull()
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            statusMessage = downloadedPath?.let { path -> AppI18n.t("downloaded_to", path) },
                            lastDownloadedPaths = paths
                        )
                    }
                    if (downloadedPath != null) {
                        onDownloadedPath(downloadedPath)
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

    fun dismissPreview() {
        _uiState.update {
            it.copy(
                previewItem = null,
                previewText = null,
                previewImage = null,
                previewPages = emptyList(),
                isPreviewLoading = false,
                canDownloadPreview = false
            )
        }
    }
}
