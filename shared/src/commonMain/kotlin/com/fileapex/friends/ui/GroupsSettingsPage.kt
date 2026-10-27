package com.fileapex.friends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.FriendsLimits
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.ui.settings.SettingsPageShell
import com.fileapex.i18n.stringRes
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.theme.settingsCardColors
import com.fileapex.ui.theme.settingsCardShape
import kotlinx.coroutines.launch

/** Header and switches share this width and are centered in it, so they stay aligned at any size. */
private val AUTO_ACCEPT_COLUMN: Dp = 96.dp

@Composable
fun groupsRowSubtitle(): String {
    val friends by FriendsHub.friends.collectAsState()
    return if (friends.isEmpty()) stringRes("groups_subtitle_none") else stringRes("groups_subtitle_count", friends.size.toString())
}

@Composable
fun GroupsSettingsPage(layoutMode: SettingsScreenLayoutMode, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shape = settingsCardShape()
    val friends by FriendsHub.friends.collectAsState()
    var hideIp by remember { mutableStateOf(FileApexServices.settings.friendsHideIp()) }
    var removeOf by remember { mutableStateOf<FriendLinkEntity?>(null) }
    var maxMb by remember { mutableStateOf(FileApexServices.settings.friendsMaxFileMb()) }

    SettingsPageShell(title = stringRes("groups_title"), layoutMode = layoutMode, onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth(), shape = shape, colors = settingsCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringRes("groups_about"), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { FriendsUi.open() }) { Text(stringRes("groups_open_friends")) }
                }
            }
            Card(Modifier.fillMaxWidth(), shape = shape, colors = settingsCardColors()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringRes("friends_hide_ip"), fontWeight = FontWeight.Medium)
                        Text(stringRes("friends_hide_ip_warning"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = hideIp, onCheckedChange = {
                        hideIp = it
                        FileApexServices.settings.setFriendsHideIp(it)
                        FriendsHub.hideIpChanged()
                    })
                }
            }
            Card(Modifier.fillMaxWidth(), shape = shape, colors = settingsCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringRes("groups_max_file_title"), fontWeight = FontWeight.Medium)
                    Text(stringRes("groups_max_file_desc"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FriendsLimits.choicesMb.forEach { mb ->
                            FilterChip(
                                selected = maxMb == mb,
                                onClick = { maxMb = mb; FileApexServices.settings.setFriendsMaxFileMb(mb) },
                                label = { Text(if (mb == 0) stringRes("groups_max_file_unlimited") else "$mb MB") },
                                colors = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.onSurface, selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    }
                }
            }
            if (friends.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), shape = shape, colors = settingsCardColors()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringRes("groups_auto_accept_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringRes("groups_header_name"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            Box(Modifier.width(AUTO_ACCEPT_COLUMN), contentAlignment = Alignment.Center) {
                                Text(stringRes("groups_header_auto_accept"), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                            }
                            Spacer(Modifier.width(48.dp))
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        friends.groupBy { it.groupName }.forEach { (group, members) ->
                            Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                            members.forEach { friend ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(friend.label, modifier = Modifier.weight(1f), maxLines = 1)
                                    Box(Modifier.width(AUTO_ACCEPT_COLUMN), contentAlignment = Alignment.Center) {
                                        Switch(checked = friend.autoAccept, onCheckedChange = { scope.launch { FriendsHub.setAutoAccept(friend.friendKey, it) } })
                                    }
                                    IconButton(onClick = { removeOf = friend }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringRes("friends_remove"), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    removeOf?.let { f ->
        AlertDialog(
            onDismissRequest = { removeOf = null },
            title = { Text(stringRes("friends_remove") + " " + f.label + "?") },
            text = { Text(stringRes("groups_remove_confirm")) },
            confirmButton = { Button(onClick = { scope.launch { FriendsHub.remove(f.friendKey) }; removeOf = null }) { Text(stringRes("friends_remove")) } },
            dismissButton = { TextButton(onClick = { removeOf = null }) { Text(stringRes("cancel")) } }
        )
    }
}
