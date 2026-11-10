package com.fileapex.drop

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.util.TimeUtils
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

enum class ShareFailure { NotAvailable, NotAFile, NoRelay, TooMany, CouldNotStart, NotAccepted }

sealed interface ShareResult {
    class Ready(val url: String, val info: DropInfo) : ShareResult
    data class Failed(val reason: ShareFailure) : ShareResult
}

/**
 * Starts the drop endpoint when a link is made and stops it as soon as no link is live, so nothing listens while nothing is
 * shared. The endpoint key is new on every start and is never stored: a link cannot outlive the run that made it.
 */
class GuestDropService(
    private val engine: () -> GuestDropEngine?,
    private val scope: CoroutineScope,
    private val drops: GuestDrops,
    private val now: () -> Long = TimeUtils::now,
    /** False until the owner accepted that links go through a relay; no link is made before that. */
    private val allowed: () -> Boolean = { true },
    /** Lists the ids the drop relay lets in. Without it, or when it cannot be reached, links use the ordinary relay. */
    private val registrar: DropRelayRegistrar = DropRelayRegistrar.None,
    private val dropRelayUrl: String = DROP_RELAY_URL
) {
    private val lock = Mutex()
    private var endpoint: GuestDropEndpoint? = null
    private var serving: Job? = null

    /** True while the running endpoint is on the drop relay; [senderId] is the id the relay's registry lists for it. */
    private var endpointOnDropRelay = false
    private var senderId = ""
    private val activeInternal = MutableStateFlow<List<DropInfo>>(emptyList())
    private val serveSlots = Semaphore(MAX_SERVES)

    private class FileFacts(val sizeBytes: Long, val lastModifiedMs: Long)

    /** True while the endpoint is listening: from the first link until the last download was acknowledged or the links ended. */
    fun endpointIsUp(): Boolean = endpoint?.isAlive == true

    /** The live links, for the share screen. */
    val active: StateFlow<List<DropInfo>> = activeInternal.asStateFlow()

    suspend fun share(file: File, ttlMs: Long): ShareResult = lock.withLock {
        if (!allowed()) return ShareResult.Failed(ShareFailure.NotAccepted)
        val started = engine() ?: return ShareResult.Failed(ShareFailure.NotAvailable)
        val facts = withContext(Dispatchers.IO) { if (file.isFile) FileFacts(file.length(), file.lastModified()) else null }
            ?: return ShareResult.Failed(ShareFailure.NotAFile)
        val name = FriendsWire.safeName(file.name)
        val created = drops.create(file.path, name, facts.sizeBytes, ttlMs, facts.lastModifiedMs) ?: return ShareResult.Failed(ShareFailure.TooMany)
        try {
            val guestIds = GuestDropKeys.guestIds(created.secret)
            val running = endpoint?.takeIf { it.isAlive }
            var keyed = false
            val ep = if (running != null) {
                if (endpointOnDropRelay) {
                    // The endpoint is already on the drop relay, so this link needs its guest ids listed or its visitors are turned away.
                    if (!registrar.register(listOf(senderId) + guestIds, created.expiresAtMs)) {
                        abandon(created.id)
                        return ShareResult.Failed(ShareFailure.CouldNotStart)
                    }
                    keyed = true
                }
                running
            } else {
                val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val id = GuestDropKeys.endpointId(key)
                keyed = registrar.register(listOf(id) + guestIds, created.expiresAtMs)
                try {
                    var bound = start(started, key, if (keyed) dropRelayUrl else null)
                    if (keyed && bound.relayUrl() == null) {
                        // The drop relay did not take the endpoint (not reachable, or not the relay that lists ids): use the ordinary relay.
                        closeEndpoint()
                        keyed = false
                        bound = start(started, key, null)
                    }
                    endpointOnDropRelay = keyed
                    senderId = id
                    bound
                } catch (_: java.io.IOException) {
                    abandon(created.id)
                    return ShareResult.Failed(ShareFailure.CouldNotStart)
                }
            }
            val relay = ep.relayUrl()
            if (relay == null) {
                abandon(created.id)
                return ShareResult.Failed(ShareFailure.NoRelay)
            }
            val url = GuestDropLink(
                ep.id, relay, created.id, created.secret, created.expiresAtMs, name, facts.sizeBytes,
                guestKeys = if (keyed) GuestDropKeys.COUNT else 0
            ).toUrl()
            val info = DropInfo(created.id, name, facts.sizeBytes, created.expiresAtMs, downloading = false)
            activeInternal.value = drops.list()
            scope.launch { delay((created.expiresAtMs - now()).coerceAtLeast(0L) + EXPIRY_GRACE_MS); refresh() }
            ShareResult.Ready(url, info)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { abandon(created.id) }
            throw e
        }
    }

    /** Takes a link back that never reached its owner, and stops the endpoint if nothing else uses it. */
    private suspend fun abandon(id: String) {
        drops.revoke(id)
        closeIfIdle()
        activeInternal.value = drops.list()
    }

    suspend fun revoke(id: String) {
        drops.revoke(id)
        refresh()
    }

    suspend fun revokeAll() {
        drops.list().forEach { drops.revoke(it.id) }
        refresh()
    }

    /** Re-reads the live links and stops the endpoint when none is left. */
    suspend fun refresh() {
        activeInternal.value = drops.list()
        lock.withLock { closeIfIdle() }
    }

    private suspend fun start(engine: GuestDropEngine, key: ByteArray, relayUrl: String?): GuestDropEndpoint {
        val ep = engine.start(key, relayUrl = relayUrl)
        endpoint = ep
        serving = scope.launch {
            while (true) {
                val stream = ep.accept() ?: break
                if (!serveSlots.tryAcquire()) {
                    stream.abort()
                    continue
                }
                scope.launch {
                    try {
                        GuestDropServer.serve(stream, drops, onGranted = { refresh() }, onSpent = { refresh() })
                    } finally {
                        serveSlots.release()
                    }
                    refresh()
                }
            }
        }
        return ep
    }

    /** Stops the endpoint once no link is live and no stream is still being answered, so a finished download keeps its connection until its receipt. */
    private suspend fun closeIfIdle() {
        if (drops.list().isNotEmpty() || serveSlots.availablePermits < MAX_SERVES) return
        closeEndpoint()
    }

    private suspend fun closeEndpoint() {
        val ep = endpoint ?: return
        endpoint = null
        endpointOnDropRelay = false
        senderId = ""
        serving?.cancel()
        serving = null
        ep.close()
    }

    companion object {
        /** Streams read at once; a visitor beyond this is turned away without waiting. */
        const val MAX_SERVES = 16
        private const val EXPIRY_GRACE_MS = 1_000L

        /** The relay that carries guest drops; the same host as the paired-device relay, which tells the two apart by token. */
        const val DROP_RELAY_URL = "https://relay.getfileapex.com"
    }
}
