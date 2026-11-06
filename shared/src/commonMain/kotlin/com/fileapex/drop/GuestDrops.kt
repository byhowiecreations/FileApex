package com.fileapex.drop

import com.fileapex.util.TimeUtils
import com.fileapex.util.sha256Hex
import java.security.MessageDigest
import java.security.SecureRandom

/** Why a download was not started. A wrong secret and an unknown id are the same answer on purpose. */
enum class DropRefusal(val code: String) {
    Denied("denied"),
    Expired("expired"),
    Used("used"),
    Busy("busy")
}

class CreatedDrop(val id: String, val secret: String, val expiresAtMs: Long, val name: String, val sizeBytes: Long)

data class DropInfo(val id: String, val name: String, val sizeBytes: Long, val expiresAtMs: Long, val downloading: Boolean)

sealed interface DropBegin {
    class Granted(val lease: DropLease) : DropBegin
    class Refused(val reason: DropRefusal) : DropBegin
}

/** One running download. [complete] spends the link; [release] gives it back after a download that did not reach the end. */
class DropLease internal constructor(private val drops: GuestDrops, private val drop: GuestDrops.Entry) {
    val id: String get() = drop.id
    val path: String get() = drop.path
    /** When the file was last changed as the link was made, or null when unknown. A different value later means the file was edited. */
    val lastModifiedMs: Long? get() = drop.lastModifiedMs
    val name: String get() = drop.name
    val sizeBytes: Long get() = drop.sizeBytes

    /** True once the hard limit passed or the sender revoked the link; a running transfer stops when this turns true. */
    fun expired(): Boolean = drops.isOver(drop)

    fun complete() = drops.finish(drop, this, completed = true)

    fun release() = drops.finish(drop, this, completed = false)
}

/**
 * The sender's live links. A link serves its file once: one downloader at a time, spent when the last byte is sent, and over
 * at the hard limit whether or not anything was downloaded. Only a hash of the secret is kept.
 */
class GuestDrops(private val now: () -> Long = TimeUtils::now, private val maxLive: Int = 3) {
    internal class Entry(
        val id: String,
        val verifier: String,
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val expiresAtMs: Long,
        val lastModifiedMs: Long?
    ) {
        var lease: DropLease? = null
        var spent = false
        var revoked = false
        var failures = 0
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val random = SecureRandom()

    /** Null when [maxLive] links are already live. */
    fun create(path: String, name: String, sizeBytes: Long, ttlMs: Long, lastModifiedMs: Long? = null): CreatedDrop? = synchronized(lock) {
        sweep()
        if (entries.values.count { isLive(it) } >= maxLive) return null
        val id = hex()
        val secret = hex()
        val expires = now() + ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        entries[id] = Entry(id, verifier(secret), path, name, sizeBytes, expires, lastModifiedMs)
        CreatedDrop(id, secret, expires, name, sizeBytes)
    }

    fun begin(id: String, secret: String): DropBegin = synchronized(lock) {
        sweep()
        val entry = entries[id] ?: return DropBegin.Refused(DropRefusal.Denied)
        if (entry.revoked) return DropBegin.Refused(DropRefusal.Denied)
        if (!MessageDigest.isEqual(entry.verifier.encodeToByteArray(), verifier(secret).encodeToByteArray())) {
            if (++entry.failures >= MAX_FAILURES) entry.revoked = true
            return DropBegin.Refused(DropRefusal.Denied)
        }
        when {
            entry.spent -> DropBegin.Refused(DropRefusal.Used)
            now() > entry.expiresAtMs -> DropBegin.Refused(DropRefusal.Expired)
            entry.lease != null -> DropBegin.Refused(DropRefusal.Busy)
            else -> DropBegin.Granted(DropLease(this, entry).also { entry.lease = it })
        }
    }

    /** Ends the link and, through [DropLease.expired], any download running on it. */
    fun revoke(id: String): Boolean = synchronized(lock) {
        val entry = entries[id]?.takeIf { isLive(it) } ?: return false
        entry.revoked = true
        true
    }

    fun list(): List<DropInfo> = synchronized(lock) {
        sweep()
        entries.values.filter { isLive(it) }.map { DropInfo(it.id, it.name, it.sizeBytes, it.expiresAtMs, it.lease != null) }
    }

    internal fun isOver(entry: Entry): Boolean = synchronized(lock) { entry.revoked || now() > entry.expiresAtMs }

    internal fun finish(entry: Entry, lease: DropLease, completed: Boolean) = synchronized(lock) {
        if (entry.lease !== lease) return
        entry.lease = null
        if (completed) entry.spent = true
    }

    private fun isLive(entry: Entry) = !entry.spent && !entry.revoked && now() <= entry.expiresAtMs

    /** Spent, revoked and long expired links are forgotten; a recently expired one is kept so its holder is told why. */
    private fun sweep() {
        val t = now()
        entries.values.removeAll { it.lease == null && (it.revoked || t > it.expiresAtMs + GRACE_MS) }
    }

    private fun verifier(secret: String) = sha256Hex("fileapex-guest-drop-v1|${secret.lowercase()}".encodeToByteArray())

    private fun hex() = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        const val MIN_TTL_MS = 60_000L
        const val MAX_TTL_MS = 24 * 3_600_000L
        const val DEFAULT_TTL_MS = 3_600_000L
        private const val MAX_FAILURES = 5
        private const val GRACE_MS = 3_600_000L
    }
}
