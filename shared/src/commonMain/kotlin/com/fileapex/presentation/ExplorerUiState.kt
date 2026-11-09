package com.fileapex.presentation

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.MultiCopyDeviceOption

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
    /** True once the first listing has arrived, failed, or stopped for a PIN; until then a remote device is still being accessed. */
    val firstListingSettled: Boolean = false,
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
