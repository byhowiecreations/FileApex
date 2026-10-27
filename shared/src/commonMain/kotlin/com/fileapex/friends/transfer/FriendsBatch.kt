package com.fileapex.friends.transfer

import com.fileapex.iroh.IrohStream
import com.fileapex.util.TimeUtils
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What a sender announces before sending several files: how many, how big in total, and a few names for the prompt. */
data class FriendBatch(val id: String, val count: Int, val totalBytes: Long, val firstNames: List<String>)

object FriendsBatchWire {
    const val MAX_FILES = 500
    const val MAX_NAMES = 3
    private const val MAX_NAME_CHARS = 40

    fun encode(b: FriendBatch): ByteArray =
        (listOf("b1", b.id, b.count.toString(), b.totalBytes.toString()) + b.firstNames.take(MAX_NAMES)).joinToString("\n").encodeToByteArray()

    /** Null for anything malformed or out of range; the caller drops the stream. */
    fun decode(bytes: ByteArray): FriendBatch? {
        val p = bytes.decodeToString().split("\n")
        if (p.size < 4 || p[0] != "b1" || !FriendsWire.ID.matches(p[1])) return null
        val count = p[2].toIntOrNull()?.takeIf { it in 1..MAX_FILES } ?: return null
        val total = p[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val names = p.drop(4).take(MAX_NAMES).map { FriendsWire.safeName(it).take(MAX_NAME_CHARS) }
        return FriendBatch(p[1], count, total, names)
    }
}

enum class BatchAnswer { Accepted, Refused, Unsupported }

/**
 * What a yes to a batch allows: the files the sender announced, no more, from that friend only, until [ttlMs] pass.
 * A file still has to fit the friend's size limit and the disk; this only replaces the per-file question.
 */
class FriendBatchGrants(private val now: () -> Long = { TimeUtils.now() }, private val ttlMs: Long = TTL_MS) {
    private class Grant(val friendKey: String, var countLeft: Int, var bytesLeft: Long, val expiresAtMs: Long) {
        val accepted = HashMap<String, Long>()
    }

    private val grants = LinkedHashMap<String, Grant>()

    @Synchronized fun grant(friendKey: String, batch: FriendBatch) {
        dropExpired()
        grants[batch.id] = Grant(friendKey, batch.count, batch.totalBytes, now() + ttlMs)
        while (grants.size > MAX_GRANTS) grants.remove(grants.keys.first())
    }

    /** True when [offer] belongs to a live grant of [friendKey] with room left; counts the file against it. */
    @Synchronized fun allows(friendKey: String, offer: FriendOffer): Boolean {
        if (offer.batchId.isEmpty()) return false
        dropExpired()
        val g = grants[offer.batchId]?.takeIf { it.friendKey == friendKey } ?: return false
        val earlier = g.accepted[offer.transferId]
        // The same file offered again after an interruption does not use the grant twice.
        if (earlier != null) return offer.sizeBytes <= earlier
        if (g.countLeft <= 0 || offer.sizeBytes > g.bytesLeft) return false
        g.countLeft--
        g.bytesLeft -= offer.sizeBytes
        g.accepted[offer.transferId] = offer.sizeBytes
        return true
    }

    @Synchronized fun revoke(friendKey: String) { grants.values.removeAll { it.friendKey == friendKey } }

    @Synchronized fun size(): Int = grants.size

    private fun dropExpired() { val t = now(); grants.values.removeAll { it.expiresAtMs < t } }

    companion object {
        const val MAX_GRANTS = 32
        const val TTL_MS = 30L * 60L * 1000L
    }
}

object FriendBatchNegotiation {
    private const val ANSWER_TIMEOUT_MS = 150_000L

    /** Sender side on a fresh stream whose kind byte is already written. A closed stream means the friend's app does not know batches. */
    suspend fun announce(stream: IrohStream, batch: FriendBatch, timeoutMs: Long = ANSWER_TIMEOUT_MS): BatchAnswer {
        try {
            FriendsWire.writeFrame(stream, FriendsBatchWire.encode(batch))
            val reply = withTimeoutOrNull(timeoutMs) { FriendsWire.decodeReply(FriendsWire.readFrame(stream)) } ?: return BatchAnswer.Refused
            return if (reply.first) BatchAnswer.Accepted else BatchAnswer.Refused
        } catch (_: IOException) {
            return BatchAnswer.Unsupported
        } finally {
            stream.abort()
        }
    }
}

/** Receiving side of a batch announcement: asks once through [decide], refuses at once when the whole batch cannot fit on disk. */
class FriendBatchReceiver(
    private val grants: FriendBatchGrants,
    private val decide: suspend (FriendBatch) -> Boolean,
    private val freeBytes: () -> Long,
    private val acceptTimeoutMs: Long = 120_000L
) {
    suspend fun handle(friendKey: String, stream: IrohStream) {
        try {
            val batch = FriendsBatchWire.decode(FriendsWire.readFrame(stream)) ?: return stream.abort()
            val fits = batch.totalBytes <= freeBytes() - SPARE
            val ok = fits && (withTimeoutOrNull(acceptTimeoutMs) { decide(batch) } ?: false)
            if (ok) grants.grant(friendKey, batch)
            FriendsWire.writeFrame(stream, FriendsWire.encodeReply(ok, 0))
            stream.finish()
        } catch (_: IOException) {
            stream.abort()
        }
    }

    fun allows(friendKey: String, offer: FriendOffer): Boolean = grants.allows(friendKey, offer)

    fun revoke(friendKey: String) = grants.revoke(friendKey)

    private companion object { const val SPARE = 50L * 1024 * 1024 }
}

/**
 * Sender side of one batch: the first file to start asks the friend once, the others wait for that answer.
 * [id] is the batch id to put in offers, an empty string to offer each file on its own (the friend's app has no batches
 * or could not be reached), or null when the friend said no.
 */
class FriendSendBatch(val batch: FriendBatch, private val open: suspend () -> IrohStream) {
    private val lock = Mutex()
    private var settled = false
    private var answer: String? = ""

    suspend fun id(): String? = lock.withLock {
        if (!settled) { answer = negotiate(); settled = true }
        answer
    }

    private suspend fun negotiate(): String? {
        val stream = try { open() } catch (_: IOException) { return "" }
        return when (FriendBatchNegotiation.announce(stream, batch)) {
            BatchAnswer.Accepted -> batch.id
            BatchAnswer.Refused -> null
            BatchAnswer.Unsupported -> ""
        }
    }
}
