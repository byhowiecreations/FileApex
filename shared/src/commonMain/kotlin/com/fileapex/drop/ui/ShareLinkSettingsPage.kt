package com.fileapex.drop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.drop.GuestDropHub
import com.fileapex.drop.GuestLinkConsent
import com.fileapex.i18n.stringRes
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.settings.SettingsPageShell
import com.fileapex.ui.theme.settingsCardColors
import com.fileapex.ui.theme.settingsCardShape

/** Security and access control: the one place to accept or withdraw the relay for shared links. */
@Composable
fun ShareLinkSettingsPage(layoutMode: SettingsScreenLayoutMode, onBack: () -> Unit) {
    val shape = settingsCardShape()
    val consent = GuestLinkConsent.fromStored(FileApexServices.settings.guestLinkConsent.collectAsState().value)
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        GuestLinkConsentDialog(
            onDecision = { accepted ->
                FileApexServices.settings.setGuestLinkConsent((if (accepted) GuestLinkConsent.Accepted else GuestLinkConsent.Declined).stored)
                asking = false
            },
            onDismiss = { asking = false }
        )
    }
    SettingsPageShell(title = stringRes("share_link_page_title"), layoutMode = layoutMode, onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth(), shape = shape, colors = settingsCardColors()) {
                Text(stringRes("share_link_page_about"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            Card(Modifier.fillMaxWidth(), shape = shape, colors = settingsCardColors()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringRes("share_link_page_switch"), fontWeight = FontWeight.Medium)
                        Text(shareLinkRowSubtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = consent.allowed, onCheckedChange = { on ->
                        if (on) {
                            asking = true
                        } else {
                            FileApexServices.settings.setGuestLinkConsent(GuestLinkConsent.Declined.stored)
                            GuestDropHub.stopAll()
                        }
                    })
                }
            }
        }
    }
}
