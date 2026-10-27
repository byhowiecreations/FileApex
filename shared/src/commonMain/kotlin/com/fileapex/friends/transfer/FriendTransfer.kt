package com.fileapex.friends.transfer

import com.fileapex.util.TimeUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FriendTransferDirection { SEND, RECEIVE }

enum class FriendTransferStatus {
    QUEUED, PREPARING, CONNECTING, WAITING, ACTIVE, VERIFYING, DONE, REFUSED, FAILED, CANCELED;

    val finished: Boolean get() = this == DONE || this == REFUSED || this == FAILED || this == CANCELED
}

/** One file moving to or from a friend. [speedBps] is averaged over the active part of the transfer. */
data class FriendTransfer(
    val id: String,
    val friendKey: String,
    val friendLabel: String,
    val direction: FriendTransferDirection,
    val fileName: String,
    val totalBytes: Long,
    val doneBytes: Long = 0L,
    val status: FriendTransferStatus = FriendTransferStatus.QUEUED,
    val speedBps: Long = 0L,
    /** Why a file was refused without asking: `size` or `space`. */
    val note: String = ""
) {
    val fraction: Float get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * The transfers the Groups window and the notification show. Progress updates are cut to one per [minGapMs] per
 * transfer so a fast link does not recompose the screen for every chunk; a status change always goes through.
 */
class FriendsTransferBoard(
    private val scope: CoroutineScope,
    private val now: () -> Long = { TimeUtils.now() },
    private val minGapMs: Long = 150L,
    private val keepFinishedMs: Long = 60_000L,
    private val onChange: (FriendTransfer) -> Unit = {}
) {
    private val internal = MutableStateFlow<List<FriendTransfer>>(emptyList())
    val transfers: StateFlow<List<FriendTransfer>> = internal.asStateFlow()
    private val lastEmit = ConcurrentHashMap<String, Long>()
    private val activeSince = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun get(id: String): FriendTransfer? = internal.value.firstOrNull { it.id == id }

    fun put(transfer: FriendTransfer) {
        internal.update { list -> if (list.any { it.id == transfer.id }) list.map { if (it.id == transfer.id) transfer else it } else list + transfer }
        lastEmit[transfer.id] = now()
        onChange(transfer)
    }

    fun status(id: String, status: FriendTransferStatus) {
        val current = get(id) ?: return
        if (current.status == status) return
        if (status == FriendTransferStatus.ACTIVE) activeSince[id] = now() to current.doneBytes
        val updated = current.copy(status = status, doneBytes = if (status == FriendTransferStatus.DONE) current.totalBytes else current.doneBytes)
        put(updated)
        if (status.finished) {
            activeSince.remove(id)
            scope.launch { delay(keepFinishedMs); drop(id) }
        }
    }

    fun progress(id: String, done: Long) {
        val current = get(id) ?: return
        val t = now()
        if (t - (lastEmit[id] ?: 0L) < minGapMs) return
        val (since, fromBytes) = activeSince[id] ?: (t to done)
        val elapsed = t - since
        val speed = if (elapsed > 0) (done - fromBytes) * 1000 / elapsed else 0L
        put(current.copy(doneBytes = done, speedBps = speed.coerceAtLeast(0L)))
    }

    fun drop(id: String) {
        internal.update { list -> list.filterNot { it.id == id } }
        lastEmit.remove(id)
        activeSince.remove(id)
    }
}

/** Files waiting to be sent, first in first out. The hub keeps one active send per friend and takes the next from here. */
class FriendsSendQueue {
    /** [owned] marks a private copy the queue made for a share; it is deleted when the transfer ends or is dropped. */
    data class Item(val id: String, val friendKey: String, val file: File, val owned: Boolean = false, val batch: FriendSendBatch? = null)

    private val items = ArrayDeque<Item>()

    @Synchronized fun add(item: Item) { items.addLast(item) }

    @Synchronized fun take(friendKey: String, skipLarge: Boolean = false, largeBytes: Long = Long.MAX_VALUE): Item? {
        val at = items.indexOfFirst { it.friendKey == friendKey && !(skipLarge && it.file.length() >= largeBytes) }
        return if (at < 0) null else items.removeAt(at)
    }

    /** The item when it was still waiting and is now gone. */
    @Synchronized fun removeItem(id: String): Item? {
        val at = items.indexOfFirst { it.id == id }
        return if (at < 0) null else items.removeAt(at)
    }

    /** True when the item was still waiting and is now gone. */
    @Synchronized fun remove(id: String): Boolean = items.removeAll { it.id == id }

    @Synchronized fun idsFor(friendKey: String): List<String> = items.filter { it.friendKey == friendKey }.map { it.id }
}
