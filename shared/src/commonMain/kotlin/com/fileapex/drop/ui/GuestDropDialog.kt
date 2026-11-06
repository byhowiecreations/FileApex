package com.fileapex.drop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.drop.DropInfo
import com.fileapex.drop.GuestLinkConsent
import com.fileapex.drop.GuestDropHub
import com.fileapex.drop.GuestDropLimits
import com.fileapex.drop.GuestDropStrings
import com.fileapex.drop.ShareResult
import com.fileapex.drop.timeLeftText
import com.fileapex.i18n.stringRes
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isDesktopHost
import com.fileapex.util.TimeUtils
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Phase {
    data object Choose : Phase
    data object Working : Phase
    class Ready(val url: String, val info: DropInfo) : Phase
    class Failed(val messageKey: String) : Phase
}

/**
 * Makes a one-download link for [file] and shows it as a QR code and text. Closing the window leaves the link live.
 * Until the owner has accepted that the link goes through a relay server, only the consent screen is shown.
 */
@Composable
fun GuestDropDialog(file: File, onDismiss: () -> Unit) {
    val consent = GuestLinkConsent.fromStored(FileApexServices.settings.guestLinkConsent.collectAsState().value)
    when {
        consent.mustAsk -> GuestLinkConsentDialog(
            onDecision = { accepted ->
                FileApexServices.settings.setGuestLinkConsent((if (accepted) GuestLinkConsent.Accepted else GuestLinkConsent.Declined).stored)
                if (!accepted) onDismiss()
            },
            onDismiss = onDismiss
        )
        consent.allowed -> GuestDropShareDialog(file, onDismiss)
        else -> LaunchedEffect(Unit) { onDismiss() }
    }
}

@Composable
private fun GuestDropShareDialog(file: File, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var limit by remember { mutableStateOf(GuestDropLimits.default) }
    var phase by remember { mutableStateOf<Phase>(Phase.Choose) }
    var copied by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(TimeUtils.now()) }
    val active by GuestDropHub.service.active.collectAsState()
    LaunchedEffect(phase, active.isNotEmpty()) {
        while (phase is Phase.Ready || active.isNotEmpty()) {
            now = TimeUtils.now()
            delay(1000)
        }
    }
    val ready = phase as? Phase.Ready
    val message = ready?.let { stringRes("share_link_message", it.info.name, it.url) }.orEmpty()
    val live = ready != null && active.any { it.id == ready.info.id } && ready.info.expiresAtMs > now
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("share_link_title")) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                if (phase !is Phase.Ready && active.isNotEmpty()) OpenLinks(active, now) { id -> scope.launch { GuestDropHub.service.revoke(id) } }
                when (val p = phase) {
                    Phase.Choose -> {
                        Text(stringRes("share_link_limit"), style = MaterialTheme.typography.bodySmall)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GuestDropLimits.choices.forEach { c ->
                                FilterChip(selected = limit == c, onClick = { limit = c }, label = { Text(stringRes(c.labelKey)) })
                            }
                        }
                        Text(stringRes("share_link_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Phase.Working -> Text(stringRes("share_link_creating"))
                    is Phase.Failed -> Text(stringRes(p.messageKey), color = MaterialTheme.colorScheme.error)
                    is Phase.Ready -> {
                        if (live) {
                            val painter = rememberQrCodePainter(p.url) { errorCorrectionLevel = QrErrorCorrectionLevel.Low }
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
                                Image(painter, contentDescription = null, modifier = Modifier.size(200.dp))
                            }
                            Text(stringRes("share_link_expires", timeLeftText(p.info.expiresAtMs - now)), style = MaterialTheme.typography.bodyMedium)
                            Text(stringRes("share_link_note"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (copied) Text(stringRes("share_link_copied"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(stringRes("share_link_closed"))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                Phase.Choose, is Phase.Failed -> Button(onClick = {
                    phase = Phase.Working
                    scope.launch {
                        phase = when (val r = GuestDropHub.service.share(file, limit.ttlMs)) {
                            is ShareResult.Ready -> Phase.Ready(r.url, r.info)
                            is ShareResult.Failed -> Phase.Failed(GuestDropStrings.forFailure(r.reason))
                        }
                    }
                }) { Text(stringRes("share_link_create")) }
                Phase.Working -> Unit
                is Phase.Ready -> if (live) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { PlatformClipboard.setSystemClipboardText(p.url); copied = true }) { Text(stringRes("share_link_copy")) }
                        if (!isDesktopHost()) {
                            OutlinedButton(onClick = { PlatformClipboard.sharePlainText(message, "FileApex") }) {
                                Text(stringRes("share_link_share"))
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            ShareDialogCloseButtons(
                stop = if (live) ({ scope.launch { GuestDropHub.service.revoke((phase as Phase.Ready).info.id) } }) else null,
                onClose = onDismiss
            )
        }
    )
}

/** Links that are already open, each with its time left and a Stop, so a link can be ended without finding its file again. */
@Composable
private fun OpenLinks(links: List<DropInfo>, now: Long, onStop: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringRes("share_link_open_links"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        links.forEach { link ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(link.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    val state = if (link.downloading) " · ${stringRes("share_link_downloading")}" else ""
                    Text(timeLeftText(link.expiresAtMs - now) + state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onStop(link.id) }) { Text(stringRes("share_link_stop"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ShareDialogCloseButtons(stop: (() -> Unit)?, onClose: () -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        if (stop != null) TextButton(onClick = stop) { Text(stringRes("share_link_stop"), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onClose) { Text(stringRes("close")) }
    }
}
