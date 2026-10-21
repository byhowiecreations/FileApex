package com.fileapex.ui.settings

import com.fileapex.ui.SettingsMenuIds
import com.fileapex.di.FileApexServices
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.data.settings.traits
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.adaptive.CompactHomeTitleBand
import com.fileapex.ui.adaptive.CompactHomeTitleStyle
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.theme.fileApexTopAppBarColors

internal val LocalSettingsHomeAction = compositionLocalOf<(() -> Unit)?> { null }

internal val LocalSettingsExitAction = compositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun DisclosureScreen(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    whatText: String,
    whyText: String,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onAgree: () -> Unit
) {
    SettingsPageShell(
        title = title,
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Box(
            modifier = contentModifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringRes("what_label"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = whatText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringRes("why_label"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = whyText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAgree,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringRes("agree_and_continue"))
                    }
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringRes("cancel"))
                    }
                }
            }
        }
    }
}

internal fun clipboardShareTargetsSubtitle(state: SettingsUiState): String {
    return when (state.clipboardShareMode) {
        ClipboardShareMode.SPECIFIC -> AppI18n.t("specific_devices")
        ClipboardShareMode.ALL -> AppI18n.t("all_devices")
        ClipboardShareMode.UNSET -> AppI18n.t("choose_devices")
    }
}

internal fun localizedPinIdle(timeout: PinIdleTimeout): String = when (timeout) {
    PinIdleTimeout.Immediate -> AppI18n.t("pin_idle_immediate")
    PinIdleTimeout.OneMinute -> AppI18n.t("pin_idle_1m")
    PinIdleTimeout.FiveMinutes -> AppI18n.t("pin_idle_5m")
    PinIdleTimeout.TenMinutes -> AppI18n.t("pin_idle_10m")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageShell(
    title: String,
    layoutMode: SettingsScreenLayoutMode,
    onBack: (() -> Unit)?,
    onOpenTransferQueue: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val containerColor = if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.background
    val onHome = LocalSettingsHomeAction.current
    val onExit = LocalSettingsExitAction.current

    when (layoutMode) {
        SettingsScreenLayoutMode.FullScreen -> {
            Scaffold(
                containerColor = containerColor,
                topBar = { SettingsTopBar(title = title, onBack = onBack) }
            ) { padding ->
                content(Modifier.fillMaxSize().padding(padding))
            }
        }

        SettingsScreenLayoutMode.ListPane -> {
            Column(modifier = Modifier.fillMaxSize()) {
                FileApexPaneSectionHeader(title = title, onBack = onBack)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
        SettingsScreenLayoutMode.CompactShell -> {
            val isFreestyle = currentTheme.traits.canvasHome
            val homeAction: @Composable RowScope.() -> Unit = {
                if (isFreestyle && onHome != null) {
                    IconButton(
                        onClick = onHome,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = stringRes("home"),
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (onBack != null) {
                    FileApexPaneSectionHeader(
                        title = title,
                        onBack = onBack,
                        actions = homeAction
                    )
                } else {
                    CompactHomeTitleBand(
                        primaryLine = "FileApex",
                        secondaryLine = title,
                        style = CompactHomeTitleStyle.Prominent,
                        onOpenTransferQueue = onOpenTransferQueue,
                        showCloseService = onExit != null,
                        onCloseService = onExit,
                        actions = homeAction
                    )
                }
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringRes("back")
                    )
                }
            }
        },
        colors = fileApexTopAppBarColors()
    )
}

@Composable
internal fun SettingsNavItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val headlineColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val trailingTint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val destructiveColors = if (isDestructive) {
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            headlineColor = MaterialTheme.colorScheme.onErrorContainer,
            supportingColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingIconColor = MaterialTheme.colorScheme.error,
            trailingIconColor = MaterialTheme.colorScheme.error
        )
    } else {
        ListItemDefaults.colors()
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = destructiveColors,
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        headlineContent = { Text(title, color = headlineColor, softWrap = true) },
        supportingContent = { Text(subtitle, softWrap = true) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = trailingTint
            )
        }
    )
}


@Composable
internal fun rememberAdvancedSettingsInUse(state: SettingsUiState): Set<String> {
    val settings = FileApexServices.settings
    val tailscaleOn by settings.tailscaleEnabled.collectAsState()
    val irohOn by settings.irohEnabled.collectAsState()
    val backup by settings.backupConfig.collectAsState()
    return buildSet {
        if (tailscaleOn) add(SettingsMenuIds.TAILSCALE)
        if (irohOn) add(SettingsMenuIds.IROH)
        if (state.googleDriveRelayEnabled) add(SettingsMenuIds.DRIVE_RELAY)
        if (state.googleAccountLinkEnabled) add(SettingsMenuIds.GOOGLE_ACCOUNT)
        if (state.pinRequiredEnabled) add(SettingsMenuIds.PIN_REQUIRED)
        if (backup.enabled) add(SettingsMenuIds.BACKUP_SYNC)
    }
}

@Composable
internal fun SettingsAdvancedSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringRes("settings_show_advanced")) },
        supportingContent = { Text(stringRes("settings_show_advanced_desc")) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}
