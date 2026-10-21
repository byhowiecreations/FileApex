package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.PlatformClipboard
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.theme.settingsCardShape

private const val REPO_URL = "https://github.com/byhowiecreations/FileApex"
private const val LICENSES_URL = "$REPO_URL/blob/main/THIRD_PARTY_LICENSES.md"
private const val TAILSCALE_URL = "https://github.com/tailscale/tailscale"
private const val IROH_URL = "https://github.com/n0-computer/iroh"

@Composable
internal fun SettingsAdvancedAndAboutRows(showAdvanced: Boolean, onShowAdvancedChange: (Boolean) -> Unit, onOpenAbout: () -> Unit) {
    SettingsAdvancedSwitchRow(showAdvanced, onShowAdvancedChange)
    SettingsNavItem(title = stringRes("about_title"), subtitle = stringRes("about_desc"), onClick = onOpenAbout)
    Spacer(modifier = Modifier.height(56.dp))
}

@Composable
internal fun AboutSettingsPage(layoutMode: SettingsScreenLayoutMode, onBack: () -> Unit) {
    SettingsPageShell(title = stringRes("about_title"), layoutMode = layoutMode, onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AboutCard(stringRes("about_fileapex_heading"), stringRes("about_fileapex_body"), stringRes("about_source_link"), REPO_URL)
            AboutCard(stringRes("about_tailscale_heading"), stringRes("about_tailscale_body"), stringRes("about_source_link"), TAILSCALE_URL)
            AboutCard(stringRes("about_iroh_heading"), stringRes("about_iroh_body"), stringRes("about_source_link"), IROH_URL)
            AboutCard(stringRes("about_licenses_heading"), stringRes("about_licenses_body"), stringRes("about_licenses_link"), LICENSES_URL)
            Text(
                text = stringRes("about_trademarks"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutCard(heading: String, body: String, linkLabel: String, url: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = settingsCardShape()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { PlatformClipboard.openUrlInDefaultBrowser(url) }) { Text(linkLabel) }
        }
    }
}
