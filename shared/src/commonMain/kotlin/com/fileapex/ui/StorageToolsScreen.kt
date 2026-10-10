package com.fileapex.ui

import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.KineticStyleLook
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.i18n.stringRes
import com.fileapex.platform.CleanupKind
import com.fileapex.platform.DuplicateGroup
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.FolderUsage
import com.fileapex.platform.StorageCategory
import com.fileapex.platform.StorageFileEntry
import com.fileapex.platform.StorageScan
import com.fileapex.platform.StorageToolsEvents
import com.fileapex.platform.TrashedEntry
import com.fileapex.platform.childFolders
import com.fileapex.platform.currentTimeMillis
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.platform.findDuplicateGroups
import com.fileapex.platform.folderUsages
import com.fileapex.platform.formatHostFileSize
import com.fileapex.platform.listTrashed
import com.fileapex.platform.localPathInfo
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.purgeTrashed
import com.fileapex.platform.restoreTrashed
import com.fileapex.platform.scanStorage
import com.fileapex.platform.staleFiles
import com.fileapex.platform.trashLocalEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RingUsed = Color(0xFF4DA3FF)
private val RingTrack = Color(0xFF1D4E89)
private val RingCard = Color(0xFF12161C)

/** Below this the screen is a single column; at or above it the ring sits beside the tiles. */
private val SideBySideWidth = 600.dp
private val ThreeColumnWidth = 900.dp
private val ListMaxWidth = 840.dp

private sealed interface ToolsPage {
    data object Home : ToolsPage
    data object Largest : ToolsPage
    data class Category(val category: StorageCategory) : ToolsPage
    data object Duplicates : ToolsPage
    data object Folders : ToolsPage
    data class Cleanup(val kind: CleanupKind) : ToolsPage
    data object Trash : ToolsPage
}

private val ageChoices = listOf(30 to "storage_age_30d", 90 to "storage_age_90d", 365 to "storage_age_1y")
private const val DEFAULT_STALE_DAYS = 90

private fun StorageCategory.icon(): ExplorerIcon = when (this) {
    StorageCategory.Images -> ExplorerIcon.Images
    StorageCategory.Videos -> ExplorerIcon.Videos
    StorageCategory.Audio -> ExplorerIcon.Audio
    StorageCategory.Apk -> ExplorerIcon.Apk
    StorageCategory.Documents -> ExplorerIcon.Documents
}

@Composable
private fun StorageCategory.label(): String = stringRes("storage_cat_$key")

@Composable
private fun CleanupKind.label(): String = stringRes(
    if (this == CleanupKind.Screenshots) "storage_old_screenshots" else "storage_old_downloads"
)

/**
 * Default text colour for screens drawn straight onto a theme's background. Dark and glass themes get
 * light ink; Scaffold-less content would otherwise fall back to a dark default and vanish.
 */
@Composable
internal fun themedContentColor(): Color {
    val traits = LocalAppTheme.current.traits
    val onDarkChrome = traits.orbitalHome || traits.fluxSurfaces || traits.glassChrome
    return if (onDarkChrome) {
        if (LocalKineticStyle.current == KineticStyle.JADED_STEEL) KineticStyleLook.ink else Color(0xFFE4EEEF)
    } else {
        LocalContentColor.current
    }
}

internal class ToolsItemActions(
    val copy: (RemoteFileItem) -> Unit,
    val send: (RemoteFileItem) -> Unit,
)

private val LocalToolsItemActions = staticCompositionLocalOf<ToolsItemActions?> { null }

private data class Tile(val icon: ExplorerIcon, val label: String, val summary: String, val target: ToolsPage)

/**
 * Storage analyser for the file manager: usage ring, per-type breakdown, folder sizes, duplicates,
 * cleanup suggestions, favorites and the trash. Replaces the explorer body while open.
 */
@Composable
internal fun StorageToolsScreen(
    onCopyItem: (RemoteFileItem) -> Unit,
    onSendItem: (RemoteFileItem) -> Unit,
    onClose: () -> Unit,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val root = remember { defaultStorageRoot().trimEnd('/') }
    var page by remember { mutableStateOf<ToolsPage>(ToolsPage.Home) }
    var scan by remember { mutableStateOf<StorageScan?>(null) }
    var trash by remember { mutableStateOf<List<TrashedEntry>>(emptyList()) }
    var duplicates by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var folderPath by remember { mutableStateOf(root) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { scanStorage() to listTrashed() } }
            result.onSuccess { (fresh, trashed) ->
                scan = fresh
                trash = trashed
                duplicates = null
                error = null
            }.onFailure { error = UserFacingErrors.message(it, "storage_scan_failed") }
            loading = false
        }
    }

    val folders = remember(scan) { scan?.let { folderUsages(it.everything, root) }.orEmpty() }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(Unit) { StorageToolsEvents.changed.collect { reload() } }
    LaunchedEffect(page, scan) {
        val current = scan
        if (page == ToolsPage.Duplicates && duplicates == null && current != null) {
            duplicates = withContext(Dispatchers.IO) { findDuplicateGroups(current.everything) }
        }
    }
    fun goBack() {
        when {
            page == ToolsPage.Home -> onClose()
            page == ToolsPage.Folders && folderPath != root -> folderPath = folderPath.substringBeforeLast('/')
            else -> page = ToolsPage.Home
        }
    }
    FileApexBackHandler { goBack() }

    val ink = themedContentColor()
    CompositionLocalProvider(
        LocalContentColor provides ink,
        LocalToolsItemActions provides ToolsItemActions(onCopyItem, onSendItem)
    ) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= SideBySideWidth
        val columns = if (maxWidth >= ThreeColumnWidth) 3 else 2
        Column(Modifier.fillMaxSize()) {
            val title = when (val p = page) {
                ToolsPage.Home -> stringRes("tools")
                ToolsPage.Largest -> stringRes("storage_largest_files")
                is ToolsPage.Category -> p.category.label()
                ToolsPage.Duplicates -> stringRes("storage_duplicates")
                ToolsPage.Folders -> stringRes("storage_folder_sizes")
                is ToolsPage.Cleanup -> p.kind.label()
                ToolsPage.Trash -> stringRes("storage_cat_trash")
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = ::goBack) {
                    Icon(explorerIcon(ExplorerIcon.Back), contentDescription = stringRes("back"))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                }
            }
            val current = scan
            when {
                current == null && loading -> CenteredNote(stringRes("storage_scanning"))
                current == null -> CenteredNote(error ?: stringRes("storage_scan_failed"))
                else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val pageModifier = if (page == ToolsPage.Home) Modifier.fillMaxSize()
                    else Modifier.fillMaxHeight().widthIn(max = ListMaxWidth).fillMaxWidth()
                    Column(pageModifier) {
                        when (val p = page) {
                            ToolsPage.Home -> ToolsHome(current, trash, wide, columns, { page = it }, onOpenFolder)
                            ToolsPage.Largest -> FileListPage(current.largest, scope, ::reload, onError = { error = it })
                            is ToolsPage.Category -> FileListPage(
                                current.byCategory[p.category].orEmpty(), scope, ::reload, onError = { error = it }
                            )
                            ToolsPage.Duplicates -> DuplicatesPage(duplicates, scope, ::reload) { error = it }
                            ToolsPage.Folders -> FolderSizesPage(
                                folders, folderPath, root,
                                onDrill = { folderPath = it },
                                onOpenFolder = onOpenFolder
                            )
                            is ToolsPage.Cleanup -> CleanupPage(p.kind, current, scope, ::reload) { error = it }
                            ToolsPage.Trash -> TrashPage(trash, scope, ::reload) { error = it }
                        }
                    }
                }
            }
            error?.takeIf { scan != null }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
        }
    }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolsHome(
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringRes("storage_percent_used", (usage.fraction * 100).toInt()),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
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

/** One selectable file line shared by every list page. Long-press opens the same menu as the file manager. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: StorageFileEntry, checked: Boolean, anySelected: Boolean, onToggle: () -> Unit) {
    val actions = LocalToolsItemActions.current
    var menuOpen by remember { mutableStateOf(false) }
    val item = remember(file) {
        RemoteFileItem(
            name = file.name,
            absolutePath = file.path,
            sizeBytes = file.sizeBytes,
            lastModified = file.modifiedMillis,
            isDirectory = false,
            mimeType = file.mimeType
        )
    }
    val open = { openLocalFile(file.path, file.name) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (anySelected) onToggle() else open() },
                    onLongClick = { menuOpen = true }
                )
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Box {
                Icon(
                    ExplorerEntryIcons.iconForFile(file.name, file.mimeType),
                    contentDescription = null,
                    tint = RingUsed,
                    modifier = Modifier.size(24.dp)
                )
                FavoriteMark(file.path, Modifier.align(Alignment.TopEnd))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    file.path.substringBeforeLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(formatHostFileSize(file.sizeBytes), style = MaterialTheme.typography.labelLarge)
        }
        if (actions != null) {
            ItemContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                isRemoteTarget = false,
                onCopy = { actions.copy(item) },
                onSendToDevice = { actions.send(item) },
                onDownload = {},
                item = item,
                onOpen = open,
                onSelect = onToggle
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** Bottom bar offering Move to Trash for the current selection; moves go through the system trash. */
@Composable
private fun TrashSelectionBar(
    count: Int,
    bytes: Long,
    working: Boolean,
    onTrash: () -> Unit,
) {
    if (count == 0) return
    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${stringRes("storage_selected_count", count)} · ${formatHostFileSize(bytes)}",
            modifier = Modifier.weight(1f)
        )
        Button(
            enabled = !working,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            onClick = onTrash
        ) {
            Icon(explorerIcon(ExplorerIcon.Delete), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringRes("storage_move_to_trash"))
        }
    }
}

private fun trashPaths(
    scope: CoroutineScope,
    paths: List<String>,
    reload: () -> Unit,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch {
        val outcome = withContext(Dispatchers.IO) { runCatching { trashLocalEntries(paths) } }
        outcome.onSuccess { finished ->
            onError(null)
            if (finished) reload()
        }.onFailure { onError(UserFacingErrors.message(it, "trash_failed")) }
        onDone()
    }
}

@Composable
private fun FileListPage(
    files: List<StorageFileEntry>,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
    header: @Composable () -> Unit = {},
) {
    var selected by remember(files) { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        header()
        if (files.isEmpty()) {
            CenteredNote(stringRes("storage_empty_list"))
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringRes("storage_item_count", files.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                selected = if (selected.size == files.size) emptySet() else files.map { it.path }.toSet()
            }) { Text(stringRes("storage_select_all")) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.path }) { file ->
                val checked = file.path in selected
                FileRow(file, checked, selected.isNotEmpty()) {
                    selected = if (checked) selected - file.path else selected + file.path
                }
            }
        }
        TrashSelectionBar(
            count = selected.size,
            bytes = files.filter { it.path in selected }.sumOf { it.sizeBytes },
            working = working
        ) {
            working = true
            trashPaths(scope, selected.toList(), reload, onError) {
                selected = emptySet()
                working = false
            }
        }
    }
}

@Composable
private fun CleanupPage(
    kind: CleanupKind,
    scan: StorageScan,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
) {
    var days by remember { mutableStateOf(DEFAULT_STALE_DAYS) }
    val files = remember(scan, kind, days) { staleFiles(scan.everything, kind, days, currentTimeMillis()) }
    FileListPage(files, scope, reload, onError) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringRes("storage_older_than"), style = MaterialTheme.typography.labelLarge)
            ageChoices.forEach { (value, key) ->
                if (value == days) {
                    Button(onClick = { days = value }) { Text(stringRes(key)) }
                } else {
                    OutlinedButton(onClick = { days = value }) { Text(stringRes(key)) }
                }
            }
        }
    }
}

@Composable
private fun DuplicatesPage(
    groups: List<DuplicateGroup>?,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
) {
    if (groups == null) {
        CenteredNote(stringRes("storage_duplicates_scan"))
        return
    }
    if (groups.isEmpty()) {
        CenteredNote(stringRes("storage_duplicates_none"))
        return
    }
    var selected by remember(groups) { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    val all = remember(groups) { groups.flatMap { it.files } }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringRes("storage_reclaimable", formatHostFileSize(groups.sumOf { it.wastedBytes })),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // Files within a group are ordered oldest first, so the first is the one to keep.
            TextButton(onClick = {
                selected = groups.flatMap { it.files.drop(1) }.map { it.path }.toSet()
            }) { Text(stringRes("storage_keep_oldest")) }
        }
        LazyColumn(Modifier.weight(1f)) {
            groups.forEach { group ->
                item(key = "header-${group.files.first().path}") {
                    Text(
                        stringRes("storage_duplicate_group", group.files.size, formatHostFileSize(group.sizeBytes)),
                        style = MaterialTheme.typography.labelLarge,
                        color = RingUsed,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
                    )
                }
                items(group.files, key = { it.path }) { file ->
                    val checked = file.path in selected
                    FileRow(file, checked, selected.isNotEmpty()) {
                        selected = if (checked) selected - file.path else selected + file.path
                    }
                }
            }
        }
        TrashSelectionBar(
            count = selected.size,
            bytes = all.filter { it.path in selected }.sumOf { it.sizeBytes },
            working = working
        ) {
            working = true
            trashPaths(scope, selected.toList(), reload, onError) {
                selected = emptySet()
                working = false
            }
        }
    }
}

@Composable
private fun FolderSizesPage(
    usages: Map<String, FolderUsage>,
    folderPath: String,
    root: String,
    onDrill: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val children = remember(usages, folderPath) { childFolders(usages, folderPath) }
    val total = usages[folderPath]?.bytes ?: children.sumOf { it.bytes }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    folderPath.removePrefix(root).ifBlank { "/" },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatHostFileSize(total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onOpenFolder(folderPath) }) { Text(stringRes("storage_open_in_files")) }
        }
        if (children.isEmpty()) {
            CenteredNote(stringRes("storage_empty_list"))
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(children, key = { it.path }) { folder ->
                val share = if (total <= 0L) 0f else (folder.bytes.toFloat() / total).coerceIn(0f, 1f)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onDrill(folder.path) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(explorerIcon(ExplorerIcon.Folder), contentDescription = null, tint = RingUsed, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            folder.path.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatHostFileSize(folder.bytes), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(RingTrack.copy(alpha = 0.35f))
                    ) {
                        Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(RingUsed))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun TrashPage(
    trash: List<TrashedEntry>,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
) {
    var selected by remember(trash) { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<List<TrashedEntry>?>(null) }
    if (trash.isEmpty()) {
        CenteredNote(stringRes("trash_empty_state"))
        return
    }
    fun run(ids: List<String>, action: (List<String>) -> Boolean) {
        scope.launch {
            working = true
            val outcome = withContext(Dispatchers.IO) { runCatching { action(ids) } }
            outcome.onSuccess { finished ->
                onError(null)
                if (finished) reload()
            }.onFailure { onError(UserFacingErrors.message(it, "trash_failed")) }
            selected = emptySet()
            working = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringRes("storage_item_count", trash.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                selected = if (selected.size == trash.size) emptySet() else trash.map { it.id }.toSet()
            }) { Text(stringRes("storage_select_all")) }
            TextButton(enabled = !working, onClick = { confirmPurge = trash }) {
                Text(stringRes("trash_empty"), color = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(trash, key = { it.id }) { entry ->
                val checked = entry.id in selected
                val toggle = { selected = if (checked) selected - entry.id else selected + entry.id }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggle)
                        .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Icon(
                        if (entry.isDirectory) explorerIcon(ExplorerIcon.Folder)
                        else ExplorerEntryIcons.iconForFile(entry.name, ""),
                        contentDescription = null,
                        tint = RingUsed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        entry.originalPath?.let {
                            Text(
                                stringRes("trash_from_location", it.substringBeforeLast('/')),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(formatHostFileSize(entry.sizeBytes), style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringRes("storage_selected_count", selected.size), modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !working, onClick = { run(selected.toList(), ::restoreTrashed) }) {
                    Icon(explorerIcon(ExplorerIcon.Restore), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringRes("trash_restore"))
                }
                Button(
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { confirmPurge = trash.filter { it.id in selected } }
                ) { Text(stringRes("trash_delete_forever")) }
            }
        }
    }
    confirmPurge?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text(stringRes("trash_purge_title")) },
            text = {
                Text(stringRes("trash_purge_body", "${doomed.size} · ${formatHostFileSize(doomed.sumOf { it.sizeBytes })}"))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = null
                    run(doomed.map { it.id }, ::purgeTrashed)
                }) { Text(stringRes("trash_delete_forever"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = null }) { Text(stringRes("cancel")) } }
        )
    }
}

/**
 * Folder with a gear tucked in the corner. The gear sits in a cut-out so both read clearly on any background.
 */
@Composable
internal fun ToolsGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val gear = size * 0.52f
    Box(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Icon(
            explorerIcon(ExplorerIcon.Folder, ExplorerIconFamily.Outlined),
            contentDescription = stringRes("tools"),
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(gear + 4.dp)
                .drawBehind { drawCircle(Color.Black, radius = this.size.minDimension / 2f, blendMode = BlendMode.Clear) }
        )
        Icon(
            Icons.Filled.Settings,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(gear)
        )
    }
}

/** Header button that opens Tools; matches the raised tiles next to it on Jaded Steel. */
@Composable
internal fun ToolsHeaderButton(onClick: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    if (isFileApexTiledChrome()) {
        Box(modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            JadedRaisedTile(tileSize = 28.dp) { ToolsGlyph(tint = fileApexTileTint(), size = 18.dp) }
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier) { ToolsGlyph(tint = tint) }
    }
}
