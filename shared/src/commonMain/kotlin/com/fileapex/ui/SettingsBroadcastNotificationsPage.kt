package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.notifications.NotificationAppGroup
import com.fileapex.domain.notifications.NotificationAppInfo
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isNotificationAccessGranted
import com.fileapex.platform.listNotificationApps
import com.fileapex.platform.openNotificationAccessSettings
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun BroadcastNotificationsSettingsPage(
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    val settings = FileApexServices.settings
    val enabled by settings.notificationBroadcastEnabled.collectAsState()
    val targetId by settings.notificationBroadcastTargetDeviceId.collectAsState()
    val codes by settings.notificationBroadcastVerificationCodes.collectAsState()
    val dismissal by settings.notificationBroadcastSyncDismissal.collectAsState()
    val allowedApps by settings.notificationBroadcastApps.collectAsState()
    val devices by FileApexServices.deviceRepository.observeDevices().collectAsState(emptyList())
    val pairedDevices = remember(devices) {
        val selfId = loadLocalIdentity().deviceId
        devices.filter { !it.isRemoved && it.deviceId != LocalIdentity.LOCAL_DEVICE_ID && it.deviceId != selfId }
            .distinctBy { it.deviceId }
    }
    var accessGranted by remember { mutableStateOf(isNotificationAccessGranted()) }
    var appsExpanded by remember { mutableStateOf(false) }
    var devicesExpanded by remember { mutableStateOf(false) }
    var openGroups by remember { mutableStateOf(emptySet<NotificationAppGroup>()) }
    var apps by remember { mutableStateOf<List<NotificationAppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            accessGranted = isNotificationAccessGranted()
            delay(1_000)
        }
    }
    LaunchedEffect(appsExpanded) {
        if (appsExpanded && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) { listNotificationApps() }
        }
    }

    SettingsPageShell(
        title = stringRes("broadcast_notifications"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FileApexPaneSectionHeader(title = stringRes("broadcast_notifications"))
            Text(
                stringRes("broadcast_notifications_desc"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            ListItem(
                headlineContent = { Text(stringRes("broadcast_enable"), softWrap = true) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { settings.setNotificationBroadcastEnabled(it) }
                    )
                }
            )
            if (enabled && !accessGranted) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringRes("broadcast_access_needed"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(stringRes("broadcast_access_body"), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = ::openNotificationAccessSettings,
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text(stringRes("broadcast_open_access")) }
                        if (!FileApexServices.isPlayStoreBuild) {
                            Text(
                                stringRes("broadcast_restricted_help"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            OutlinedButton(
                                onClick = onOpenAppDetailsSettings,
                                modifier = Modifier.padding(top = 4.dp)
                            ) { Text(stringRes("broadcast_open_app_info")) }
                        }
                    }
                }
            }
            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringRes("broadcast_codes"), softWrap = true) },
                supportingContent = { Text(stringRes("broadcast_codes_desc"), softWrap = true) },
                trailingContent = {
                    Switch(
                        checked = codes,
                        onCheckedChange = { settings.setNotificationBroadcastVerificationCodes(it) },
                        enabled = enabled
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringRes("broadcast_dismissal"), softWrap = true) },
                supportingContent = { Text(stringRes("broadcast_dismissal_desc"), softWrap = true) },
                trailingContent = {
                    Switch(
                        checked = dismissal,
                        onCheckedChange = { settings.setNotificationBroadcastSyncDismissal(it) },
                        enabled = enabled
                    )
                }
            )
            HorizontalDivider()

            if (pairedDevices.size > 1) {
                val effectiveTarget = pairedDevices.firstOrNull { it.deviceId == targetId }
                CollapsibleHeader(
                    title = stringRes("broadcast_target"),
                    summary = effectiveTarget?.deviceName,
                    expanded = devicesExpanded,
                    onToggle = { devicesExpanded = !devicesExpanded }
                )
                if (devicesExpanded) {
                    pairedDevices.forEach { device ->
                        ListItem(
                            modifier = Modifier.clickable { settings.setNotificationBroadcastTargetDeviceId(device.deviceId) },
                            headlineContent = { Text(device.deviceName) },
                            leadingContent = {
                                RadioButton(
                                    selected = device.deviceId == effectiveTarget?.deviceId,
                                    onClick = { settings.setNotificationBroadcastTargetDeviceId(device.deviceId) }
                                )
                            }
                        )
                    }
                }
                HorizontalDivider()
            }

            CollapsibleHeader(
                title = stringRes("broadcast_apps"),
                summary = null,
                supporting = stringRes("broadcast_apps_desc"),
                expanded = appsExpanded,
                onToggle = { appsExpanded = !appsExpanded }
            )
            if (appsExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                NotificationAppGroup.entries.forEach { group ->
                    val inGroup = apps.filter { it.group == group }
                    if (inGroup.isNotEmpty()) {
                        val enabledCount = inGroup.count { it.packageName in allowedApps }
                        val groupOpen = group in openGroups
                        CollapsibleHeader(
                            title = stringRes(groupTitleKey(group)),
                            summary = if (enabledCount > 0) stringRes("broadcast_apps_on_count", enabledCount.toString()) else null,
                            expanded = groupOpen,
                            onToggle = { openGroups = if (groupOpen) openGroups - group else openGroups + group },
                            modifier = Modifier.padding(start = 16.dp)
                        )
                        if (groupOpen) {
                            inGroup.forEachIndexed { index, app ->
                                val on = app.packageName in allowedApps
                                val setOn: (Boolean) -> Unit = { next ->
                                    settings.setNotificationBroadcastApps(
                                        if (next) allowedApps + app.packageName else allowedApps - app.packageName
                                    )
                                }
                                if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 40.dp, end = 16.dp))
                                ListItem(
                                    modifier = Modifier
                                        .padding(start = 24.dp)
                                        .clickable(enabled = enabled) { setOn(!on) },
                                    headlineContent = { Text(app.label) },
                                    trailingContent = {
                                        Switch(checked = on, onCheckedChange = setOn, enabled = enabled)
                                    }
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
            }
        }
    }
}

private fun groupTitleKey(group: NotificationAppGroup): String = when (group) {
    NotificationAppGroup.MESSAGING -> "broadcast_group_messages"
    NotificationAppGroup.EMAIL -> "broadcast_group_email"
    NotificationAppGroup.SHOPPING -> "broadcast_group_shopping"
    NotificationAppGroup.AI -> "broadcast_group_ai"
    NotificationAppGroup.HEALTH -> "broadcast_group_health"
    NotificationAppGroup.WEATHER -> "broadcast_group_weather"
    NotificationAppGroup.UTILITIES -> "broadcast_group_utilities"
    NotificationAppGroup.OTHER -> "broadcast_group_other"
}

@Composable
private fun CollapsibleHeader(
    title: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    ListItem(
        modifier = modifier.clickable(onClick = onToggle),
        headlineContent = { Text(title, softWrap = true) },
        supportingContent = (supporting ?: summary)?.let { text -> { Text(text, softWrap = true) } },
        trailingContent = {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null
            )
        }
    )
}
