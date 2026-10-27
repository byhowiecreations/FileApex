package com.fileapex.friends.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fileapex.di.FileApexServices
import com.fileapex.friends.ActiveInvite
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.i18n.formatLocalizedDateTime
import com.fileapex.i18n.stringRes
import com.fileapex.util.TimeUtils
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isDesktopHost
import com.fileapex.platform.rememberDownloadsFilePicker
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the Friends entry points toggle. Every theme's "Connect with friends" item calls [open]. */
object FriendsUi {
    enum class Stage { Closed, Intro, Panel }
    enum class Mode { List, Invite, Enter }

    /** Which step the one Friends window shows. */
    val mode = MutableStateFlow(Mode.List)

    val stage = MutableStateFlow(Stage.Closed)
    /** Window position of the open panel; a card released outside it was dropped onto home. */
    var panelBounds: androidx.compose.ui.geometry.Rect? = null
    /** An invite link that arrived by tap; the panel opens on "Enter an invite" with it filled in. */
    val incomingInvite = MutableStateFlow<String?>(null)

    /** The explanation shows the first time only; after that the entry opens the panel directly. */
    fun open() { if (incomingInvite.value == null) mode.value = Mode.List; stage.value = if (FileApexServices.settings.friendsIntroSeen()) Stage.Panel else Stage.Intro }

    fun openWithInvite(text: String) { incomingInvite.value = text; mode.value = Mode.Enter; open() }
}

/** Placed once at the app root. Shows the explanation, the Friends panel and Accept prompts. */
@Composable
fun FriendsHost() {
    val stage by FriendsUi.stage.collectAsState()
    val offers by FriendsHub.pendingOffers.collectAsState()
    var joinedName by remember { mutableStateOf<String?>(null) }
    var addedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { FriendsHub.joined.collect { joinedName = it } }
    LaunchedEffect(Unit) { FriendsHub.added.collect { addedName = it } }

    when (stage) {
        FriendsUi.Stage.Intro -> AlertDialog(
            onDismissRequest = { FriendsUi.stage.value = FriendsUi.Stage.Closed },
            title = { Text(stringRes("friends_intro_title")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringRes("friends_intro_body")) } },
            confirmButton = { Button(onClick = { FileApexServices.settings.setFriendsIntroSeen(true); FriendsUi.stage.value = FriendsUi.Stage.Panel }) { Text(stringRes("friends_continue")) } },
            dismissButton = { TextButton(onClick = { FriendsUi.stage.value = FriendsUi.Stage.Closed }) { Text(stringRes("cancel")) } }
        )
        FriendsUi.Stage.Panel -> Dialog(
            onDismissRequest = { FriendsUi.stage.value = FriendsUi.Stage.Closed },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // The window stays full screen and never changes size: a size animation inside a wrap-content dialog resizes the window every frame and MagicOS takes seconds to follow. Only the panel inside animates.
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { FriendsUi.stage.value = FriendsUi.Stage.Closed },
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(shown, enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.92f)) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)
                            .animateContentSize(tween(220))
                            .onGloballyPositioned { FriendsUi.panelBounds = it.boundsInRoot() }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    ) { FriendsPanel(onClose = { FriendsUi.stage.value = FriendsUi.Stage.Closed }) }
                }
            }
        }
        FriendsUi.Stage.Closed -> Unit
    }

    offers.firstOrNull()?.let { offer ->
        var always by remember(offer.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringRes("friends_offer_title", offer.friendLabel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringRes("friends_offer_from", offer.friendLabel))
                    Text(if (offer.fileCount > 1) offer.fileName else stringRes("friends_offer_file", offer.fileName))
                    Text(stringRes("friends_offer_size", formatSize(offer.sizeBytes)))
                    if (offer.fileCount == 1) Text(stringRes("friends_offer_type", fileTypeLabel(offer.fileName)))
                    if (offer.offeredAtMs > 0L) Text(stringRes("friends_offer_time", formatLocalizedDateTime(offer.offeredAtMs, TimeUtils.DEFAULT_ZONE_ID)))
                    Spacer(Modifier.height(4.dp))
                    Text(stringRes("friends_offer_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { always = !always }) {
                        Checkbox(checked = always, onCheckedChange = { always = it })
                        Text(stringRes("friends_offer_always"))
                    }
                }
            },
            confirmButton = { Button(onClick = { FriendsHub.decide(offer.id, true, always) }) { Text(stringRes("friends_accept")) } },
            dismissButton = { TextButton(onClick = { FriendsHub.decide(offer.id, false) }) { Text(stringRes("friends_decline")) } }
        )
    }
    FriendReceivedHost()
    addedName?.let { name ->
        AlertDialog(
            onDismissRequest = { addedName = null },
            title = { Text(stringRes("friends_added", name)) },
            confirmButton = { TextButton(onClick = { addedName = null }) { Text(stringRes("ok")) } }
        )
    }
    joinedName?.let { name ->
        AlertDialog(
            onDismissRequest = { joinedName = null },
            title = { Text(stringRes("friends_joined", name)) },
            confirmButton = { TextButton(onClick = { joinedName = null }) { Text(stringRes("ok")) } }
        )
    }
}

private typealias PanelMode = FriendsUi.Mode

@Composable
private fun FriendsPanel(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val friends by FriendsHub.friends.collectAsState()
    val transfers by FriendsHub.transfers.collectAsState()
    val pinned by FriendsHub.pinned.collectAsState()
    val incomingText by FriendsUi.incomingInvite.collectAsState()
    val mode by FriendsUi.mode.collectAsState()
    fun setMode(m: PanelMode) { FriendsUi.mode.value = m }
    LaunchedEffect(incomingText) { if (incomingText != null) setMode(PanelMode.Enter) }
    LaunchedEffect(Unit) { FriendsHub.joined.collect { setMode(PanelMode.List) } }
    LaunchedEffect(Unit) { FriendsHub.added.collect { setMode(PanelMode.List) } }
    var hideIp by remember { mutableStateOf(FileApexServices.settings.friendsHideIp()) }
    var sendTo by remember { mutableStateOf<FriendLinkEntity?>(null) }
    var renameOf by remember { mutableStateOf<FriendLinkEntity?>(null) }
    var removeOf by remember { mutableStateOf<FriendLinkEntity?>(null) }
    val pick = rememberFriendFilePicker { picked ->
        val target = sendTo
        sendTo = null
        if (picked.isNotEmpty() && target != null) scope.launch { FriendsHub.enqueueSend(target.friendKey, picked) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringRes(when (mode) { PanelMode.Invite -> "friends_invite"; PanelMode.Enter -> "friends_enter"; PanelMode.List -> "groups_title" }), style = MaterialTheme.typography.headlineSmall)
        if (!FriendsHub.available) {
            Text(stringRes("friends_unavailable"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onClose) { Text(stringRes("close")) }
            return@Column
        }
        when (mode) {
            PanelMode.Invite -> InvitePane(onBack = { setMode(PanelMode.List) })
            PanelMode.Enter -> EnterPane(prefill = incomingText.orEmpty(), onDone = { FriendsUi.incomingInvite.value = null; setMode(PanelMode.List) })
            PanelMode.List -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { setMode(PanelMode.Invite) }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Text(stringRes("friends_invite"), maxLines = 1, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = { setMode(PanelMode.Enter) }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Text(stringRes("friends_enter"), maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                HorizontalDivider()
                if (friends.isEmpty()) Text(stringRes("friends_none"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                friends.groupBy { it.groupName }.forEach { (group, members) ->
                    Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    members.forEach { friend ->
                        FriendCard(
                            friend = friend,
                            transfers = transfers.filter { it.friendKey == friend.friendKey },
                            pinned = friend.friendKey in pinned,
                            onSend = { sendTo = friend; pick() },
                            onRename = { renameOf = friend },
                            onRemove = { removeOf = friend },
                            onAutoAccept = { on -> scope.launch { FriendsHub.setAutoAccept(friend.friendKey, on) } },
                            onPin = { on -> FriendsHub.setPinned(friend.friendKey, on) },
                            onFilesDropped = { files -> scope.launch { FriendsHub.enqueueSend(friend.friendKey, files) } },
                            onDroppedOnHome = { FriendsHub.setPinned(friend.friendKey, true); FriendsUi.stage.value = FriendsUi.Stage.Closed },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { FriendsUi.stage.value = FriendsUi.Stage.Intro }) { Text(stringRes("friends_intro_title")) }
                    TextButton(onClick = onClose) { Text(stringRes("close")) }
                }
            }
        }
    }

    renameOf?.let { f ->
        var text by remember(f.friendKey) { mutableStateOf(f.label) }
        AlertDialog(
            onDismissRequest = { renameOf = null },
            title = { Text(stringRes("friends_rename")) },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true) },
            confirmButton = { Button(onClick = { scope.launch { FriendsHub.rename(f.friendKey, text) }; renameOf = null }) { Text(stringRes("save")) } },
            dismissButton = { TextButton(onClick = { renameOf = null }) { Text(stringRes("cancel")) } }
        )
    }
    removeOf?.let { f ->
        AlertDialog(
            onDismissRequest = { removeOf = null },
            title = { Text(stringRes("friends_remove") + " " + f.label + "?") },
            confirmButton = { Button(onClick = { scope.launch { FriendsHub.remove(f.friendKey) }; removeOf = null }) { Text(stringRes("friends_remove")) } },
            dismissButton = { TextButton(onClick = { removeOf = null }) { Text(stringRes("cancel")) } }
        )
    }
}

@Composable
private fun InvitePane(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val groups = listOf("friends_group_friends", "friends_group_family", "friends_group_coworkers", "friends_group_other")
    var groupKey by remember { mutableStateOf(groups[0]) }
    var custom by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val invite by FriendsHub.activeInvite.collectAsState()
    val genericError = stringRes("friends_error_generic")
    val groupLabel = stringRes(groupKey)

    val active: ActiveInvite? = invite
    if (active == null) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { k -> FilterChip(
                selected = groupKey == k, onClick = { groupKey = k }, label = { Text(stringRes(k)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
            ) }
        }
        if (groupKey == "friends_group_other") OutlinedTextField(custom, { custom = it.take(40) }, label = { Text(stringRes("friends_group_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringRes("friends_your_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && myName.isNotBlank(), onClick = {
                busy = true; error = null
                val group = if (groupKey == "friends_group_other") custom.ifBlank { groupLabel } else groupLabel
                scope.launch {
                    runCatching { FriendsHub.createInvite(group, myName.trim()) }.onFailure { error = it.message ?: genericError }
                    busy = false
                }
            }) { Text(stringRes("friends_create_invite")) }
            OutlinedButton(onClick = onBack) { Text(stringRes("cancel")) }
        }
    } else {
        val painter = rememberQrCodePainter(active.uri) { errorCorrectionLevel = QrErrorCorrectionLevel.Low }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
                Image(painter, contentDescription = null, modifier = Modifier.size(220.dp))
            }
            Text(stringRes("friends_pin_help"), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Text(active.pin.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(stringRes("friends_invite_expires"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val message = stringRes("friends_invite_message", active.uri)
            var copied by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                if (!isDesktopHost()) Button(onClick = { PlatformClipboard.sharePlainText(message, "FileApex") }) { Text(stringRes("friends_share_link")) }
                // The desktop share would open a web page and a messenger may wrap a custom link as a .url file: copy plain text instead.
                if (isDesktopHost()) Button(onClick = { PlatformClipboard.setSystemClipboardText(message); copied = true }) { Text(stringRes("friends_copy_message")) }
                OutlinedButton(onClick = { PlatformClipboard.setSystemClipboardText(active.uri); copied = true }) { Text(stringRes("friends_copy_link")) }
            }
            if (copied) Text(stringRes("friends_link_copied"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { FriendsHub.cancelInvite(); onBack() }) { Text(stringRes("close")) }
        }
    }
}

@Composable
private fun EnterPane(prefill: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(prefill) }
    var pin by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    val genericError = stringRes("friends_error_generic")
    OutlinedTextField(link, { link = it }, label = { Text(stringRes("friends_paste_invite")) }, maxLines = 2, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        pin,
        { pin = it.filter(Char::isDigit).take(6) },
        label = { Text(stringRes("friends_pin")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringRes("friends_your_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    added?.let { Text(stringRes("friends_added", it), color = MaterialTheme.colorScheme.primary) }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && pin.length == 6 && myName.isNotBlank() && link.isNotBlank(), onClick = {
            busy = true; error = null
            scope.launch {
                FriendsHub.join(link, pin, myName.trim())
                    .onSuccess { added = it; FriendsUi.incomingInvite.value = null; onDone() }
                    .onFailure { error = it.message ?: genericError }
                busy = false
            }
        }) { Text(stringRes(if (busy) "friends_joining" else "friends_join")) }
        OutlinedButton(onClick = onDone) { Text(stringRes("close")) }
    }
}

private fun fileTypeLabel(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = java.net.URLConnection.guessContentTypeFromName(name)
    return when {
        mime != null && ext.isNotEmpty() -> "$mime (.$ext)"
        mime != null -> mime
        ext.isNotEmpty() -> ".$ext"
        else -> "-"
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
