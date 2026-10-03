package com.fileapex.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.fileapex.domain.model.RemoteFileItem

class ExplorerMutations(
    val rename: (RemoteFileItem, String) -> Unit,
    val compress: (RemoteFileItem) -> Unit,
    val uncompress: (RemoteFileItem) -> Unit,
    val delete: (RemoteFileItem) -> Unit,
    val importDropped: (List<String>) -> Unit,
)

val LocalExplorerMutations = staticCompositionLocalOf<ExplorerMutations?> { null }
