package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isFullDiskAccessGranted
import com.fileapex.platform.openFullDiskAccessSettings
import com.fileapex.ui.SettingsScreenLayoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullDiskAccessSettingsPage(
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit
) {
    var granted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { granted = withContext(Dispatchers.IO) { isFullDiskAccessGranted() } }
    SettingsPageShell(
        title = stringRes("full_disk_access"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("full_disk_access"), softWrap = true) },
                supportingContent = { Text(stringRes("mac_full_disk_body"), softWrap = true) },
                trailingContent = granted?.let { isOn -> { Text(stringRes(if (isOn) "on" else "off")) } }
            )
            Button(
                onClick = { openFullDiskAccessSettings() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringRes("mac_full_disk_open"))
            }
        }
    }
}
