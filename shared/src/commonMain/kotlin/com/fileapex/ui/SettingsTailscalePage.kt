package com.fileapex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.PlatformClipboard
import com.fileapex.tailscale.TAILSCALE_ADMIN_KEYS_URL
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.TailscalePhase
import com.fileapex.tailscale.TailscaleUiState
import com.fileapex.ui.theme.settingsCardShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TailscaleSettingsPage(
    layoutMode: SettingsScreenLayoutMode,
    setupExpanded: Boolean,
    onSetupExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val link by TailscaleNodeRuntime.state.collectAsState()
    var draft by rememberSaveable(link.authKey) { mutableStateOf(link.authKey) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val shape = settingsCardShape()
    val switchUnlocked = link.configurationSaved || link.enabled ||
        link.phase == TailscalePhase.Up ||
        link.phase == TailscalePhase.NeedsLogin ||
        link.phase == TailscalePhase.KeyExpired ||
        link.phase == TailscalePhase.Starting
    val waitingForBrowser = link.phase == TailscalePhase.NeedsLogin || link.phase == TailscalePhase.KeyExpired
    LaunchedEffect(Unit) {
        TailscaleNodeRuntime.prepareSignIn()
    }

    SettingsPageShell(
        title = stringRes("tailscale_overlay"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringRes("tailscale_header_body"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetupExpandedChange(!setupExpanded) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringRes("tailscale_how_to_setup"),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringRes(
                                if (setupExpanded) "collapse_section" else "expand_section",
                                stringRes("tailscale_how_to_setup")
                            ),
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(if (setupExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(
                        visible = setupExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            Text(
                                text = stringRes("tailscale_signin_guide"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { TailscaleNodeRuntime.signIn() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = link.phase != TailscalePhase.Up,
                                shape = shape
                            ) {
                                Text(
                                    stringRes(
                                        if (waitingForBrowser) "tailscale_open_signin" else "tailscale_sign_in"
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            TailscaleAdvancedSection(
                                link = link,
                                draft = draft,
                                onDraftChange = { draft = it },
                                advancedOpen = advancedOpen,
                                onAdvancedOpenChange = { advancedOpen = it },
                                shape = shape
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            ListItem(
                headlineContent = { Text(stringRes("tailscale_enable"), softWrap = true) },
                supportingContent = {
                    Text(
                        text = if (switchUnlocked) {
                            stringRes("tailscale_enable_desc")
                        } else {
                            stringRes("tailscale_toggle_locked")
                        },
                        softWrap = true
                    )
                },
                trailingContent = {
                    Switch(
                        checked = link.enabled && switchUnlocked,
                        onCheckedChange = { enabled ->
                            if (switchUnlocked) TailscaleNodeRuntime.setEnabled(enabled)
                        },
                        enabled = switchUnlocked
                    )
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringRes("tailscale_status"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tailscaleStatusLabel(link),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (canSignOutOfTailscale(link)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringRes("tailscale_sign_out_body"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { TailscaleNodeRuntime.signOut() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = link.detail != "signing_out",
                            shape = shape
                        ) {
                            Text(stringRes("tailscale_sign_out"))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringRes("tailscale_disclosure_title"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringRes("tailscale_disclosure_body"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun TailscaleAdvancedSection(
    link: TailscaleUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    advancedOpen: Boolean,
    onAdvancedOpenChange: (Boolean) -> Unit,
    shape: Shape
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAdvancedOpenChange(!advancedOpen) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringRes("tailscale_advanced_users"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringRes(
                    if (advancedOpen) "collapse_section" else "expand_section",
                    stringRes("tailscale_advanced_users")
                ),
                modifier = Modifier
                    .size(24.dp)
                    .rotate(if (advancedOpen) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = advancedOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                TailscaleSetupStep(number = "1") {
                    TailscaleAdminConsoleStep()
                }
                Spacer(modifier = Modifier.height(8.dp))
                TailscaleSetupStep(number = "2") {
                    Text(
                        text = stringRes("tailscale_setup_step_2"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TailscaleSetupStep(number = "3") {
                    Text(
                        text = stringRes("tailscale_setup_step_3"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringRes("tailscale_authkey")) },
                    placeholder = { Text(stringRes("tailscale_authkey_hint")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = shape,
                    isError = link.saveDetail == "invalid_auth_key"
                )
                if (link.saveDetail == "invalid_auth_key") {
                    Text(
                        text = stringRes("tailscale_invalid_key"),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (link.configurationSaved && draft.trim() == link.authKey) {
                    Text(
                        text = stringRes("tailscale_saved"),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { TailscaleNodeRuntime.saveConfiguration(draft) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape
                ) {
                    Text(stringRes("tailscale_save_configuration"))
                }
                if (link.enabled || link.configurationSaved) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { TailscaleNodeRuntime.reauthenticate() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = shape
                    ) {
                        Text(stringRes("tailscale_reauthenticate"))
                    }
                }
            }
        }
    }
}

@Composable
private fun TailscaleSetupStep(
    number: String,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$number.",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun TailscaleAdminConsoleStep() {
    val linkColor = MaterialTheme.colorScheme.primary
    val listener = remember {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            PlatformClipboard.openUrlInDefaultBrowser(url)
        }
    }
    val lead = stringRes("tailscale_setup_step_1_lead")
    val console = stringRes("tailscale_setup_console")
    Text(
        text = buildAnnotatedString {
            append(lead)
            withLink(
                LinkAnnotation.Url(
                    url = TAILSCALE_ADMIN_KEYS_URL,
                    linkInteractionListener = listener
                )
            ) {
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(console)
                }
            }
            append(".")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
internal fun tailscaleRowSubtitle(link: TailscaleUiState): String = tailscaleStatusLabel(link)

private fun canSignOutOfTailscale(link: TailscaleUiState): Boolean =
    link.configurationSaved ||
        link.detail == "sign_out_failed" ||
        link.detail == "signing_out" ||
        link.detail == "already_connected" ||
        link.phase == TailscalePhase.Up ||
        link.phase == TailscalePhase.NeedsLogin ||
        link.phase == TailscalePhase.KeyExpired ||
        link.phase == TailscalePhase.Starting

@Composable
private fun tailscaleStatusLabel(link: TailscaleUiState): String {
    if (link.detail == "signing_out") return stringRes("tailscale_status_signing_out")
    if (link.detail == "sign_out_failed") return stringRes("tailscale_sign_out_failed")
    return when (link.phase) {
    TailscalePhase.Off -> stringRes("tailscale_status_off")
    TailscalePhase.NeedsKey -> stringRes("tailscale_status_needs_key")
    TailscalePhase.Starting -> if (link.detail == "already_connected") {
        stringRes("tailscale_status_already_connected")
    } else {
        stringRes("tailscale_status_starting")
    }
    TailscalePhase.Up -> if (link.tailnetIp.isBlank()) {
        stringRes("tailscale_status_up")
    } else {
        stringRes("tailscale_status_up_ip", link.tailnetIp)
    }
    TailscalePhase.NeedsLogin -> stringRes("tailscale_status_needs_login")
    TailscalePhase.KeyExpired -> stringRes("tailscale_status_key_expired")
    TailscalePhase.Down -> if (link.detail == "tsnet_not_linked") {
        stringRes("tailscale_status_not_linked")
    } else {
        stringRes("tailscale_status_down")
    }
    TailscalePhase.Failed -> when (link.detail) {
        "invalid_auth_key" -> stringRes("tailscale_invalid_key")
        "", "start_failed" -> stringRes("tailscale_status_failed")
        else -> stringRes("tailscale_status_failed") + ": " + link.detail
    }
    }
}
