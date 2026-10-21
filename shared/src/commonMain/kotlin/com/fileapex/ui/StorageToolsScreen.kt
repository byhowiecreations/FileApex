package com.fileapex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.i18n.stringRes
import com.fileapex.platform.CleanupKind
import com.fileapex.platform.DuplicateGroup
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.StorageCategory
import com.fileapex.platform.StorageScan
import com.fileapex.platform.StorageToolsEvents
import com.fileapex.platform.TrashedEntry
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.platform.findDuplicateGroups
import com.fileapex.platform.folderUsages
import com.fileapex.platform.listTrashed
import com.fileapex.platform.scanStorage
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.tools.CenteredNote
import com.fileapex.ui.tools.CleanupPage
import com.fileapex.ui.tools.DuplicatesPage
import com.fileapex.ui.tools.FileListPage
import com.fileapex.ui.tools.FolderSizesPage
import com.fileapex.ui.tools.ToolsHome
import com.fileapex.ui.tools.TrashPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val RingUsed = Color(0xFF4DA3FF)

internal val RingTrack = Color(0xFF1D4E89)

internal val RingCard = Color(0xFF12161C)

/** Below this the screen is a single column; at or above it the ring sits beside the tiles. */
private val SideBySideWidth = 600.dp

private val ThreeColumnWidth = 900.dp

private val ListMaxWidth = 840.dp

internal sealed interface ToolsPage {
    data object Home : ToolsPage
    data object Largest : ToolsPage
    data class Category(val category: StorageCategory) : ToolsPage
    data object Duplicates : ToolsPage
    data object Folders : ToolsPage
    data class Cleanup(val kind: CleanupKind) : ToolsPage
    data object Trash : ToolsPage
}

internal val ageChoices = listOf(30 to "storage_age_30d", 90 to "storage_age_90d", 365 to "storage_age_1y")

internal const val DEFAULT_STALE_DAYS = 90

internal fun StorageCategory.icon(): ExplorerIcon = when (this) {
    StorageCategory.Images -> ExplorerIcon.Images
    StorageCategory.Videos -> ExplorerIcon.Videos
    StorageCategory.Audio -> ExplorerIcon.Audio
    StorageCategory.Apk -> ExplorerIcon.Apk
    StorageCategory.Documents -> ExplorerIcon.Documents
}

@Composable
internal fun StorageCategory.label(): String = stringRes("storage_cat_$key")

@Composable
internal fun CleanupKind.label(): String = stringRes(
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

internal val LocalToolsItemActions = staticCompositionLocalOf<ToolsItemActions?> { null }

internal data class Tile(val icon: ExplorerIcon, val label: String, val summary: String, val target: ToolsPage)

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
