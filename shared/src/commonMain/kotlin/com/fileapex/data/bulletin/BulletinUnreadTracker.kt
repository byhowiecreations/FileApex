package com.fileapex.data.bulletin

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.settings.AppSettings
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class BulletinUnreadTracker(
    private val settings: AppSettings,
    private val repository: BulletinBoardRepository,
    scope: CoroutineScope,
    private val selfId: () -> String = { loadLocalIdentity().deviceId },
    private val now: () -> Long = TimeUtils::now
) {
    init {
        // First start: everything already on the board counts as seen.
        if (settings.bulletinSeenAtEpochMs.value == 0L) settings.setBulletinSeenAtEpochMs(now())
    }

    val unread: StateFlow<Int> = combine(repository.messages, settings.bulletinSeenAtEpochMs) { rows, seenAt ->
        BulletinUnread.count(rows, selfId(), seenAt)
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, 0)

    val batteryLow: StateFlow<List<BatteryLowAlert>> = repository.messages
        .map { BulletinUnread.batteryLow(it, selfId()) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun unreadSince(seenAt: Long) = repository.messages.map { BulletinUnread.unreadIds(it, selfId(), seenAt) }.flowOn(Dispatchers.IO)

    fun markSeen(latestStamp: Long) {
        val seenAt = maxOf(now(), latestStamp)
        val current = settings.bulletinSeenAtEpochMs.value
        // A write to the settings store is cheap but not free; the board refreshes often while open.
        if (latestStamp > current || seenAt - current >= SEEN_WRITE_GAP_MS) settings.setBulletinSeenAtEpochMs(seenAt)
    }

    private companion object {
        const val SEEN_WRITE_GAP_MS = 5_000L
    }
}
