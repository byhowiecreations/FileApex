package com.fileapex.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.ui.explorer.ExplorerEffects
import com.fileapex.ui.explorer.ExplorerInputs
import com.fileapex.ui.explorer.ExplorerProvidedContent
import com.fileapex.ui.explorer.rememberExplorerScope
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.isFileApexCleanCurved

class ExplorerHeaderCommands(
    val onSelect: () -> Unit,
    val onPaste: (() -> Unit)?,
    val onOpenTools: (() -> Unit)? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    target: BrowseTarget,
    onBack: () -> Unit,
    /**
     * Optional TopAppBar title override (e.g. "Local Files" in wide list-detail).
     * Compact full-screen explorer keeps [ExplorerUiState.deviceTitle] when null.
     */
    titleOverride: String? = null,
    embeddedInCompactShell: Boolean = false,
    onOpenTransferQueue: () -> Unit = {},
    onRegisterRefresh: (((isRefreshing: Boolean, doRefresh: () -> Unit) -> Unit))? = null,
    onRegisterHeaderCommands: ((ExplorerHeaderCommands?) -> Unit)? = null,
    onExitApp: (() -> Unit)? = null,
    paneId: ExplorerPaneId = ExplorerPaneId.Primary,
    hostSplit: Boolean = true,
    layoutExpanded: Boolean = false,
    paneLabel: String? = null,
    secondaryTarget: BrowseTarget? = null,
    readyDevices: List<com.fileapex.presentation.DeviceListRow> = emptyList(),
    onSelectSecondaryLocal: () -> Unit = {},
    onSelectSecondaryDevice: (String) -> Unit = {},
    viewModelKey: String = target.deviceId,
    showTopBar: Boolean = true,
    filterBarLeading: (@Composable (modifier: Modifier, compact: Boolean) -> Unit)? = null,
    onDropBoxFiles: ((List<String>) -> Unit)? = null,
    viewModel: ExplorerViewModel = viewModel(key = viewModelKey) { ExplorerViewModel(target) }
) {
    val explorer = rememberExplorerScope(
        ExplorerInputs(
            target = target,
            onBack = onBack,
            titleOverride = titleOverride,
            embeddedInCompactShell = embeddedInCompactShell,
            onOpenTransferQueue = onOpenTransferQueue,
            onRegisterRefresh = onRegisterRefresh,
            onRegisterHeaderCommands = onRegisterHeaderCommands,
            onExitApp = onExitApp,
            paneId = paneId,
            hostSplit = hostSplit,
            layoutExpanded = layoutExpanded,
            paneLabel = paneLabel,
            secondaryTarget = secondaryTarget,
            readyDevices = readyDevices,
            onSelectSecondaryLocal = onSelectSecondaryLocal,
            onSelectSecondaryDevice = onSelectSecondaryDevice,
            viewModelKey = viewModelKey,
            showTopBar = showTopBar,
            filterBarLeading = filterBarLeading,
            onDropBoxFiles = onDropBoxFiles,
            viewModel = viewModel
        )
    )
    explorer.ExplorerEffects()
    explorer.ExplorerProvidedContent()
}


@Composable
internal fun ExplorerNavigationAction(
    state: ExplorerUiState,
    embeddedInCompactShell: Boolean,
    onNavigate: () -> Unit,
    onBack: () -> Unit
) {
    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
    val label = when {
        state.isSelectionMode -> stringRes("cancel")
        state.canNavigateUp -> stringRes("up")
        embeddedInCompactShell -> null
        else -> null
    }
    if (label != null) {
        TextButton(onClick = onNavigate) {
            Text(
                label,
                color = if (jadedOrbital) KineticStyleLook.steel else if (embeddedInCompactShell && isFileApexCleanCurved()) Color.White else Color.Unspecified
            )
        }
    }
}

internal const val LOCAL_SECONDARY_VM_KEY = "explorer-local-secondary"

internal fun Modifier.activePaneGlow(active: Boolean, color: Color): Modifier {
    if (!active) return this
    val shape = RoundedCornerShape(12.dp)
    return this
        .padding(3.dp)
        .shadow(
            elevation = 8.dp,
            shape = shape,
            clip = false,
            ambientColor = color.copy(alpha = 0.5f),
            spotColor = color.copy(alpha = 0.35f),
        )
        .border(1.5.dp, color.copy(alpha = 0.9f), shape)
}
