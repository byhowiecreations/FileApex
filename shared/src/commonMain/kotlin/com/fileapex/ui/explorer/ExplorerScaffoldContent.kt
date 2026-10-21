package com.fileapex.ui.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.platform.isDesktopHost
import com.fileapex.ui.dnd.EXPLORER_DROP_BOX_DEST
import com.fileapex.ui.dnd.ExplorerDropHighlight

@Composable
internal fun ExplorerScope.ExplorerScaffoldContent(padding: PaddingValues) {
    with(inputs) { with(frame) { with(live) {
        val dropHighlight = remember { ExplorerDropHighlight() }
        val openFolder = state.currentPath.ifBlank { viewModel.browseRootPath() }
        val acceptDrop: (List<String>) -> Unit = { files ->
            val destination = dropHighlight.destinationOr(openFolder)
            dropHighlight.clearHover()
            if (destination == EXPLORER_DROP_BOX_DEST && onDropBoxFiles != null) {
                onDropBoxFiles(files)
            } else {
                viewModel.importDropped(files, destination)
            }
        }
        val explorerBody: @Composable () -> Unit = { ExplorerBody(dropHighlight, openFolder, acceptDrop) }
        if (isDesktopHost()) {
            Box(Modifier.fillMaxSize().padding(padding)) { explorerBody() }
        } else {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) { explorerBody() }
        }
    } } }
}
