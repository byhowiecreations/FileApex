package com.fileapex.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.notice.Notice
import com.fileapex.notice.NoticeAction
import com.fileapex.notice.NoticeUpdateTarget
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.notice.NoticeCoordinator
import com.fileapex.notice.NoticeIndicators
import com.fileapex.notice.NoticeSeverity
import com.fileapex.platform.PlatformClipboard

private const val MAX_LISTED = 3

/**
 * The notices that apply to this install, above the Bulletin Board list. They are drawn here and are not posts: they
 * are never stored with the messages, so they are not backed up, searched or sent to another device.
 */
@Composable
fun NoticeStrip(modifier: Modifier = Modifier) {
    val visible by NoticeCoordinator.notices.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(visible) { NoticeCoordinator.markListingSeen() }
    if (visible.isEmpty()) return
    val shown = if (expanded) visible else visible.take(MAX_LISTED)
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { item -> NoticeRow(item.notice) { opened = item.notice } }
        if (!expanded && visible.size > MAX_LISTED) {
            Text(
                text = stringRes("notice_more", visible.size - MAX_LISTED),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
    opened?.let { notice ->
        // The entry may have expired or been dismissed while the dialog was open.
        if (visible.none { it.notice.id == notice.id }) opened = null else NoticeDialog(notice) { opened = null }
    }
}

@Composable
private fun NoticeRow(notice: Notice, onOpen: () -> Unit) {
    val language = AppI18n.locale.language
    val alert = notice.severity != NoticeSeverity.Info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .background(if (alert) Color(0xFFE53935) else MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(if (alert) "!" else "i", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = notice.titleFor(language),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (notice.severity == NoticeSeverity.Required) stringRes("notice_required_label") else stringRes("notice_entry_label"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeDialog(notice: Notice, onClose: () -> Unit) {
    val language = AppI18n.locale.language
    LaunchedEffect(notice.id) { NoticeCoordinator.markOpened(notice.id) }
    val link = NoticeCoordinator.linkFor(notice)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(notice.titleFor(language)) },
        text = { Text(notice.textFor(language)) },
        confirmButton = {
            Row {
                if (notice.action == NoticeAction.Update) {
                    TextButton(onClick = {
                        when (val target = NoticeCoordinator.updateTarget()) {
                            NoticeUpdateTarget.InApp -> AppUpdateCoordinator.requestShowUpdateSheet()
                            is NoticeUpdateTarget.Listing -> PlatformClipboard.openUrlInDefaultBrowser(target.url)
                        }
                        onClose()
                    }) { Text(stringRes("notice_update")) }
                }
                if (link != null) TextButton(onClick = { PlatformClipboard.openUrlInDefaultBrowser(link) }) { Text(stringRes("notice_open_link")) }
                TextButton(onClick = onClose) { Text(stringRes("close")) }
            }
        },
        dismissButton = if (NoticeIndicators.canDismiss(notice)) {
            {
                TextButton(onClick = {
                    NoticeCoordinator.dismiss(notice.id)
                    onClose()
                }) { Text(stringRes("notice_dismiss")) }
            }
        } else null
    )
}
