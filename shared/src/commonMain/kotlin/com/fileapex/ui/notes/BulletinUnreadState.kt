package com.fileapex.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.remember
import com.fileapex.data.bulletin.BatteryLowAlert
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberBulletinUnreadCount(): State<Int> = remember {
    FileApexServices.bulletinUnread.flatMapLatest { it?.unread ?: flowOf(0) }
}.collectAsState(0)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberBatteryLowAlerts(): State<List<BatteryLowAlert>> = remember {
    FileApexServices.bulletinUnread.flatMapLatest { it?.batteryLow ?: flowOf(emptyList()) }
}.collectAsState(emptyList())

/**
 * Ids of the posts that were unread when the Bulletin Board opened, read once and then kept: a post that arrives while
 * the board is open is newer than the first unread one, so it never moves the divider or triggers the jump.
 */
@Composable
internal fun rememberUnreadOnEntry(): State<Set<String>> {
    val seenAtOnEntry = remember { FileApexServices.settings.bulletinSeenAtEpochMs.value }
    return produceState(emptySet<String>(), seenAtOnEntry) {
        val tracker = FileApexServices.bulletinUnread.filterNotNull().first()
        value = tracker.unreadSince(seenAtOnEntry).first()
    }
}
