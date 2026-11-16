package com.fileapex.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.localPathInfo
import com.fileapex.platform.openLocalFile
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.RingUsed
import com.fileapex.ui.StorageToolsScreen

/** Shows the Favorites or Tools page when one is open and returns true; false means the folder view shows. */
@Composable
internal fun ExplorerScope.ExplorerOpenPage(): Boolean = with(inputs) { with(frame) { with(live) {
    when {
        favoritesOpen && favoritesAvailable -> {
            ExplorerFavoritesScreen(
                onClose = { favoritesOpen = false },
                onOpenFolder = { path ->
                    favoritesOpen = false
                    viewModel.openPath(path)
                }
            )
            true
        }
        toolsOpen && toolsAvailable -> {
            StorageToolsScreen(
                onCopyItem = viewModel::copyItem,
                onSendItem = viewModel::sendItemToDevices,
                onClose = { toolsOpen = false },
                onOpenFolder = { path ->
                    toolsOpen = false
                    viewModel.openPath(path)
                }
            )
            true
        }
        else -> false
    }
} } }

@Composable
internal fun ExplorerFavoritesScreen(onClose: () -> Unit, onOpenFolder: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes("back"))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            FavoritesList(onOpenFolder)
        }
    }
}

@Composable
internal fun FavoritesList(onOpenFolder: (String) -> Unit) {
    val favorites by FileApexServices.settings.explorerFavorites.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Icon(explorerIcon(ExplorerIcon.FavoriteOn), contentDescription = null, tint = RingUsed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringRes("storage_favorites"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (favorites.isEmpty()) {
            Text(
                stringRes("storage_no_favorites"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        favorites.forEach { path ->
            val info = remember(path) { localPathInfo(path) }
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        when {
                            info == null -> Unit
                            info.isDirectory -> onOpenFolder(path)
                            else -> openLocalFile(path, name)
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    explorerIcon(if (info?.isDirectory == false) ExplorerIcon.File else ExplorerIcon.Folder),
                    contentDescription = null,
                    tint = if (info == null) MaterialTheme.colorScheme.error else RingUsed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        path.substringBeforeLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { FileApexServices.settings.toggleExplorerFavorite(path) }) {
                    Icon(explorerIcon(ExplorerIcon.FavoriteOn), contentDescription = stringRes("unfavorite"), tint = RingUsed)
                }
            }
        }
    }
}
