package com.fileapex.friends.transfer

import com.fileapex.util.TimeUtils
import java.io.File
import java.io.IOException
import java.util.UUID
import com.fileapex.iroh.IrohStream
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

    @Synchronized fun get(id: String): FriendTransfer? = internal.value.firstOrNull { it.id == id }

    @Synchronized fun put(transfer: FriendTransfer) {
        internal.update { list -> if (list.any { it.id == transfer.id }) list.map { if (it.id == transfer.id) transfer else it } else list + transfer }
        lastEmit[transfer.id] = now()
        onChange(transfer)
    }

    @Synchronized fun status(id: String, status: FriendTransferStatus, note: String = "") {
        val current = get(id) ?: return
        if (current.status.finished || current.status == status) return
        if (status == FriendTransferStatus.ACTIVE) activeSince[id] = now() to current.doneBytes
        val updated = current.copy(status = status, note = note.ifEmpty { current.note }, doneBytes = if (status == FriendTransferStatus.DONE) current.totalBytes else current.doneBytes)
        put(updated)
        if (status.finished) {
            activeSince.remove(id)
            scope.launch { delay(keepFinishedMs); drop(id) }
        }
    }

    @Synchronized fun progress(id: String, done: Long) {
        val current = get(id) ?: return
        if (current.status.finished) return
        val t = now()
        if (t - (lastEmit[id] ?: 0L) < minGapMs) return
        val (since, fromBytes) = activeSince[id] ?: (t to done)
        val elapsed = t - since
        val speed = if (elapsed > 0) (done - fromBytes) * 1000 / elapsed else 0L
        put(current.copy(doneBytes = done, speedBps = speed.coerceAtLeast(0L)))
    }

    @Synchronized fun drop(id: String) {
        internal.update { list -> list.filterNot { it.id == id } }
        lastEmit.remove(id)
        activeSince.remove(id)
    }
}

/** Files waiting to be sent, first in first out within each friend's lane limits. */
class FriendsSendQueue {
    /** [owned] marks a private copy the queue made for a share; it is deleted when the transfer ends or is dropped. */
    data class Item(val id: String, val friendKey: String, val file: File, val owned: Boolean = false, val batch: FriendSendBatch? = null)

    private val items = ArrayDeque<Item>()

    @Synchronized fun add(item: Item) { items.addLast(item) }

    @Synchronized fun enqueue(friendKey: String, label: String, files: List<File>, owned: Boolean, board: FriendsTransferBoard, scope: CoroutineScope, openBatch: suspend () -> IrohStream) {
        val prepared = files.chunked(FriendsBatchWire.MAX_FILES).flatMap { chunk ->
            val measured = chunk.map { it to it.length() }
            val total = try { measured.fold(0L) { sum, (_, size) -> Math.addExact(sum, size) } }
                catch (e: ArithmeticException) { throw IOException("Selected files exceed the supported batch size", e) }
            val batch = if (chunk.size > 1) FriendSendBatch(
                FriendBatch(UUID.randomUUID().toString().replace("-", ""), chunk.size, total, chunk.take(FriendsBatchWire.MAX_NAMES).map { it.name }), scope, openBatch
            ) else null
            measured.map { (file, size) -> Item("s${UUID.randomUUID()}", friendKey, file, owned, batch) to size }
        }
        prepared.forEach { (item, size) ->
            board.put(FriendTransfer(item.id, friendKey, label, FriendTransferDirection.SEND, item.file.name, size, status = FriendTransferStatus.QUEUED))
            items.addLast(item)
        }
    }

    @Synchronized fun take(friendKey: String, skipLarge: Boolean = false, largeBytes: Long = Long.MAX_VALUE): Item? {
        val at = items.indexOfFirst { it.friendKey == friendKey && !(skipLarge && it.file.length() >= largeBytes) }
        return if (at < 0) null else items.removeAt(at)
    }

    /** The item when it was still waiting and is now gone. */
    @Synchronized fun removeItem(id: String): Item? {
        val at = items.indexOfFirst { it.id == id }
        return if (at < 0) null else items.removeAt(at).also { it.batch?.release() }
    }

    /** True when the item was still waiting and is now gone. */
    @Synchronized fun remove(id: String): Boolean = removeItem(id) != null

    @Synchronized fun idsFor(friendKey: String): List<String> = items.filter { it.friendKey == friendKey }.map { it.id }
}
