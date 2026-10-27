package com.fileapex.ui.explorer

import androidx.compose.material3.ExperimentalMaterial3Api
import com.fileapex.ui.ExplorerNavigationAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerActionCopy
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.KineticStyleLook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerScope.ExplorerTopBar() {
    with(inputs) { with(frame) { with(live) {
        if (showTopBar && !embeddedInCompactShell && onRegisterRefresh == null) {
            TopAppBar(
                title = {
                    Text(
                        text = if (target is BrowseTarget.Local) {
                            state.currentPath.substringAfterLast('/').ifBlank { state.currentPath }
                        } else {
                            topBarTitle
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    ExplorerNavigationAction(
                        state = state,
                        embeddedInCompactShell = false,
                        onNavigate = {
                            if (!viewModel.handleBackNavigation()) {
                                onBack()
                            }
                        },
                        onBack = onBack
                    )
                },
                actions = {
                    ExplorerTopBarActions(
                        state = state,
                        embeddedInCompactShell = false,
                        viewModel = viewModel,
                        hasExternalRefresh = false,
                        onOpenTools = if (toolsAvailable) ({ toolsOpen = true }) else null,
                        onPickFiles = onPickFiles
                    )
                },
                colors = if (jadedOrbital) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = KineticStyleLook.ink,
                        navigationIconContentColor = KineticStyleLook.ink,
                        actionIconContentColor = KineticStyleLook.steel
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                }
            )
        }
    } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerScope.ExplorerCopyButtons() {
    with(inputs) { with(frame) { with(live) {
        if (showCopyFabs) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::copySelected,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null
                        )
                    },
                    text = { Text(ExplorerActionCopy.COPY_ACTION) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                ExtendedFloatingActionButton(
                    onClick = viewModel::onMultiCopyFabClick,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.CopyAll,
                            contentDescription = null
                        )
                    },
                    text = { Text(ExplorerActionCopy.SEND_TO_ACTION) },
                    containerColor = FileApexTeal,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    } } }
}
