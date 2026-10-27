package com.fileapex.ui.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.CleanupKind
import com.fileapex.platform.StorageCategory
import com.fileapex.platform.StorageFileEntry
import com.fileapex.platform.StorageScan
import com.fileapex.platform.TrashedEntry
import com.fileapex.platform.currentTimeMillis
import com.fileapex.platform.formatHostFileSize
import com.fileapex.platform.localPathInfo
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.staleFiles
import com.fileapex.ui.DEFAULT_STALE_DAYS
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.RingCard
import com.fileapex.ui.RingTrack
import com.fileapex.ui.RingUsed
import com.fileapex.ui.Tile
import com.fileapex.ui.ToolsPage
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.icon
import com.fileapex.ui.label

@Composable
internal fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ToolsHome(
    scan: StorageScan,
    trash: List<TrashedEntry>,
    wide: Boolean,
    columns: Int,
    onNavigate: (ToolsPage) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val tiles = homeTiles(scan, trash)
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UsageCard(scan) { onNavigate(ToolsPage.Largest) }
                FavoritesSection(onOpenFolder)
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                TileGrid(tiles, columns, onNavigate)
                Spacer(Modifier.height(24.dp))
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UsageCard(scan) { onNavigate(ToolsPage.Largest) }
            TileGrid(tiles, columns, onNavigate)
            FavoritesSection(onOpenFolder)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun homeTiles(scan: StorageScan, trash: List<TrashedEntry>): List<Tile> {
    val now = remember(scan) { currentTimeMillis() }
    fun summary(files: List<StorageFileEntry>) = "${files.size} · ${formatHostFileSize(files.sumOf { it.sizeBytes })}"
    val categories = StorageCategory.entries.map { category ->
        Tile(category.icon(), category.label(), summary(scan.byCategory[category].orEmpty()), ToolsPage.Category(category))
    }
    val cleanup = CleanupKind.entries.map { kind ->
        val stale = remember(scan, kind) { staleFiles(scan.everything, kind, DEFAULT_STALE_DAYS, now) }
        Tile(
            if (kind == CleanupKind.Screenshots) ExplorerIcon.Screenshots else ExplorerIcon.OldDownloads,
            kind.label(), summary(stale), ToolsPage.Cleanup(kind)
        )
    }
    return categories + Tile(
        ExplorerIcon.Trash, stringRes("storage_cat_trash"),
        "${trash.size} · ${formatHostFileSize(trash.sumOf { it.sizeBytes })}", ToolsPage.Trash
    ) + Tile(
        ExplorerIcon.Duplicates, stringRes("storage_duplicates"), stringRes("storage_tap_to_scan"), ToolsPage.Duplicates
    ) + Tile(
        ExplorerIcon.FolderSizes, stringRes("storage_folder_sizes"), stringRes("storage_by_folder"), ToolsPage.Folders
    ) + cleanup
}

@Composable
private fun TileGrid(tiles: List<Tile>, columns: Int, onNavigate: (ToolsPage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { tile ->
                    CategoryTile(tile.icon, tile.label, tile.summary, Modifier.weight(1f)) { onNavigate(tile.target) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun UsageCard(scan: StorageScan, onClick: () -> Unit) {
    val usage = scan.usage
    val sweep by animateFloatAsState(usage.fraction, tween(900), label = "storage-ring")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(RingCard)
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 26.dp.toPx()
                val arc = Size(size.width - stroke, size.height - stroke)
                val origin = Offset(stroke / 2, stroke / 2)
                drawArc(RingTrack, 0f, 360f, false, origin, arc, style = Stroke(stroke))
                if (sweep > 0f) {
                    drawArc(RingUsed, -90f, 360f * sweep, false, origin, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringRes("storage_used_of", formatHostFileSize(usage.usedBytes), formatHostFileSize(usage.totalBytes)),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    stringRes("storage_percent_used", (usage.fraction * 100).toInt()),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun CategoryTile(icon: ExplorerIcon, label: String, summary: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(explorerIcon(icon), contentDescription = null, tint = RingUsed, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FavoritesSection(onOpenFolder: (String) -> Unit) {
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
