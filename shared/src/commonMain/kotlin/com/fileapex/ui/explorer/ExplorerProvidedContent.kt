package com.fileapex.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.ui.ExplorerMutations
import com.fileapex.ui.LocalExplorerMutations
import com.fileapex.ui.LocalExplorerPaneFocus

@Composable
internal fun ExplorerScope.ExplorerProvidedContent() {
    with(inputs) { with(frame) { with(live) {
    CompositionLocalProvider(
        LocalExplorerPaneFocus provides ownedFocus,
        LocalExplorerMutations provides ExplorerMutations(
            rename = viewModel::renameItem,
            compress = viewModel::compressItem,
            uncompress = viewModel::uncompressItem,
            delete = viewModel::deleteItem,
            move = viewModel::moveItem,
            importDropped = viewModel::importDropped,
        )
    ) {
    Scaffold(
        containerColor = when {
            jadedOrbital -> Color.Transparent
            LocalAppTheme.current.traits.fluxSurfaces -> Color.Transparent
            else -> MaterialTheme.colorScheme.background
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { ExplorerTopBar() },
        floatingActionButton = { ExplorerCopyButtons() }
    ) { padding ->
        ExplorerScaffoldContent(padding)
    }
    ExplorerPinAndIntroDialogs()
    ExplorerMultiCopyPicker()
    ExplorerPreviewDialog()
    }
    } } }
}
