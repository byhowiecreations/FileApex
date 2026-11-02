package com.fileapex.friends

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.AppSettings
import com.fileapex.data.settings.SecretAvailability
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.friends.data.FriendIncomingEntity
import com.fileapex.friends.data.FriendInviteEntity
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.data.FriendsDao
import com.fileapex.friends.data.byLabel
import com.fileapex.friends.data.uniqueFriendFolder
import com.fileapex.friends.transfer.*
import com.fileapex.iroh.IrohStream
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.util.TimeUtils
import com.fileapex.util.sha256Hex
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

/** Owns the isolated Friends endpoint, relationships and file transfers. */
object FriendsHub {
    private var settingsProvider: () -> AppSettings = { FileApexServices.settings }
    private var downloadsProvider: () -> String = ::defaultDownloadsDir
    private var engineProvider: () -> FriendsEngine? = ::embeddedFriendsEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val friendChanges = Mutex()
    private val inboundJobs = ConcurrentHashMap<Job, FriendsInbound>()
    private val inboundSlots = Semaphore(16)
    private val sendSlots = Semaphore(8)
    private var dao: FriendsDao? = null
    private var endpoint: FriendsEndpoint? = null
    private var endpointHideIp = false
    private val unknownInFlight = AtomicInteger(0)

    private val offers = FriendOffers(scope, ::db, friendChanges)
    private val removalNotice = FriendRemovalNotice(scope)
    val removedYou: StateFlow<Set<String>> get() = removalNotice.removedYou
    private val inviteInternal = MutableStateFlow<ActiveInvite?>(null)
    private val joinedInternal = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val addedInternal = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val receivedInternal = MutableSharedFlow<FriendReceivedFile>(extraBufferCapacity = 8)
    private val board = FriendsTransferBoard(scope, onChange = { FriendsNotifier.transferChanged(it) })
    private val sendQueue = FriendsSendQueue()
    private val batchGrants = FriendBatchGrants()
    private val lanes = FriendSendLanes(sendQueue)
    private val sendJobs = ConcurrentHashMap<String, Job>()
    private val receiveJobs = ConcurrentHashMap<String, Job>()
    private val streams = ConcurrentHashMap<String, IrohStream>()
    private val partFiles = ConcurrentHashMap<String, File>()
    private val userCanceled = ConcurrentHashMap.newKeySet<String>()

    /** Offers waiting for the user's Accept. */
    val pendingOffers: StateFlow<List<PendingFriendOffer>> = offers.pendingOffers
    val activeInvite: StateFlow<ActiveInvite?> = inviteInternal.asStateFlow()
    /** Label of a friend who just joined through this device's invite. */
    val joined: SharedFlow<String> = joinedInternal.asSharedFlow()
    /** Label of the friend this device just joined through someone else's invite. */
    val added: SharedFlow<String> = addedInternal.asSharedFlow()
    /** Each finished receive, for the in-app card and the background notification. */
    val received: SharedFlow<FriendReceivedFile> = receivedInternal.asSharedFlow()
    /** Pinned friends first, then the rest by name, for the share sheet shortcuts. */
    fun shareTargets(max: Int): List<FriendShareTarget> {
        if (!available) return emptyList()
        val pinnedKeys = pinned.value
        return friends.value
            .sortedWith(compareByDescending<FriendLinkEntity> { it.friendKey in pinnedKeys }.thenBy { it.label.lowercase() })
            .take(max).map { FriendShareTarget(it.friendKey, it.label, it.groupName) }
    }

    private val preselectedShare = java.util.concurrent.atomic.AtomicReference<String?>(null)
    /** A friend picked in the system share sheet; the share screen opens with that friend ticked. */
    fun preselectShare(friendKey: String) { preselectedShare.set(friendKey) }
    fun takePreselectedShare(): String? = preselectedShare.getAndSet(null)

    /** Every file moving to or from a friend, queued, active or just finished. */
    val transfers: StateFlow<List<FriendTransfer>> = board.transfers
    val hasActiveWork: Boolean get() = inboundJobs.isNotEmpty() || pendingOffers.value.isNotEmpty() || transfers.value.any { !it.status.finished }

    private val friendsInternal = MutableStateFlow<List<FriendLinkEntity>>(emptyList())
    /** The paired friends, kept current once the database is attached, so a screen has them on its first frame and its window never resizes while they load. */
    val friends: StateFlow<List<FriendLinkEntity>> = friendsInternal.asStateFlow()

    private val pinnedInternal = MutableStateFlow<Set<String>>(emptySet())
    /** Friends the user pinned to the home Devices page. Kept in a setting, no database change. */
    val pinned: StateFlow<Set<String>> = pinnedInternal.asStateFlow()

    fun setPinned(friendKey: String, on: Boolean) {
        val next = if (on) pinnedInternal.value + friendKey else pinnedInternal.value - friendKey
        pinnedInternal.value = next
        settingsProvider().setFriendsPinnedKeys(next.joinToString(","))
    }

    val available: Boolean get() = engineProvider() != null

    fun attach(database: FileApexDatabase) {
        if (dao != null) return
        dao = database.friendsDao()
        pinnedInternal.value = settingsProvider().friendsPinnedKeys().split(',').filter { it.isNotBlank() }.toSet()
        scope.launch { dao?.observeLinks()?.byLabel()?.collect { friendsInternal.value = it } }
        maintainListener(FileApexServices.presenceMonitor.onlineSnapshotEpochMs)
        scope.launch(Dispatchers.IO) { runCatching { purgeOldParts() } }
    }

    private fun db(): FriendsDao = checkNotNull(dao) { "FriendsHub.attach must run first" }

    private suspend fun links() = db().observeLinks().first()

    fun maintainListener(snapshot: Flow<Long>): Job = scope.launch {
        com.fileapex.util.cancellableCatching {
            if (dao != null && available) startAfterPairedFirst(links().isNotEmpty(), snapshot) { ensureEndpoint() }
        }.onFailure { println("FriendsHub: listener recovery failed :: ${it::class.simpleName}") }
    }

    private fun friendsKey(): ByteArray {
        val settings = settingsProvider()
        val stored = runCatching { Base64.getDecoder().decode(settings.friendsSecretKeyBase64()) }.getOrNull()
        if (stored != null && stored.size == 32) return stored
        SecretAvailability.requireAvailable(BaseAppSettings.KEY_FRIENDS_SECRET_KEY)
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(fresh))
        return fresh
    }

    private suspend fun ensureEndpoint(): FriendsEndpoint = lock.withLock {
        val hide = settingsProvider().friendsHideIp()
        endpoint?.let { if (endpointHideIp == hide && it.isAlive) return@withLock it; it.close(); endpoint = null }
        val engine = engineProvider() ?: throw IOException("Friends is not available in this build")
        val started = engine.start(friendsKey(), hide, ::admit)
        endpoint = started
        endpointHideIp = hide
        scope.launch { serve(started) }
        started
    }

    /** Called when the Hide my IP switch changes: the endpoint is rebuilt on its next use. */
    fun hideIpChanged() {
        scope.launch { lock.withLock { endpoint?.close(); endpoint = null }; runCatching { if (links().isNotEmpty()) ensureEndpoint() } }
    }

    /** A friend always; anyone else only while an unused invite exists, and then only for pairing. */
    private suspend fun admit(peerId: String): Boolean {
        val link = db().link(peerId)
        if (link != null) return true
        return inviteInternal.value?.let { it.expiresAtMs > TimeUtils.now() } == true
    }

    private suspend fun serve(ep: FriendsEndpoint) {
        while (true) {
            inboundSlots.acquire()
            var handedOff = false
            try {
                val inbound = ep.accept() ?: return
                scope.launch {
                    try { handle(inbound) } catch (e: CancellationException) { throw e } catch (e: Exception) { println("FriendsHub: stream ended :: ${e::class.simpleName} ${e.stackTrace.take(5).joinToString(">") { it.className.substringAfterLast('.') + "." + it.methodName }}") }
                }.invokeOnCompletion { inboundSlots.release() }
                handedOff = true
            } finally { if (!handedOff) inboundSlots.release() }
        }
    }

    private suspend fun handle(inbound: FriendsInbound) {
        val job = currentCoroutineContext().job
        val admitted = friendChanges.withLock {
            if (!admit(inbound.peerId)) false else { inboundJobs[job] = inbound; true }
        }
        if (!admitted) { inbound.stream.abort(); return }
        try { handleStream(inbound) } finally { inboundJobs.remove(job) }
    }

    private suspend fun handleStream(inbound: FriendsInbound) {
        val stream = inbound.stream
        val kind = try { FriendsWire.readExact(stream, 1)[0].toInt() } catch (_: IOException) { return }
        val link = db().link(inbound.peerId)
        when {
            kind == KIND_PAIR && link == null -> {
                if (unknownInFlight.incrementAndGet() > MAX_UNKNOWN) { unknownInFlight.decrementAndGet(); stream.abort(); return }
                try { handlePair(inbound.peerId, stream) } finally { unknownInFlight.decrementAndGet() }
            }
            kind == FriendRemovalNotice.KIND && link != null -> removalNotice.heard(link.friendKey)
            kind == KIND_FILE && link != null -> receive(link, stream)
            kind == KIND_BATCH && link != null ->
                FriendBatchReceiver(batchGrants, { askBatch(link, it) }, { friendDir(link.folderName).also { it.mkdirs() }.usableSpace }).handle(link.friendKey, stream)
            else -> stream.abort()
        }
    }


    suspend fun createInvite(group: String, myName: String): ActiveInvite {
        val ep = ensureEndpoint()
        val id = FriendsInviteSecrets.newId()
        val secret = FriendsInviteSecrets.newSecret()
        val pin = FriendsInviteSecrets.newPin()
        val expires = TimeUtils.now() + INVITE_LIFETIME_MS
        db().purgeInvites(TimeUtils.now())
        db().putInvite(FriendInviteEntity(id, FriendsInviteSecrets.verifier(secret, pin), group.take(40), expires))
        val invite = ActiveInvite(FriendInviteLink(id, secret, ep.address(), group.take(40), myName.take(40)).toUri(), pin, expires, group.take(40))
        inviteInternal.value = invite
        return invite
    }

    suspend fun cancelInvite() = withContext(Dispatchers.IO) {
        val current = inviteInternal.value ?: return@withContext
        FriendInviteLink.parse(current.uri)?.let { db().cancelInvite(it.inviteId) }
        inviteInternal.compareAndSet(current, null)
    }

    private suspend fun handlePair(peer: String, stream: IrohStream) {
        val parts = FriendsWire.readFrame(stream).decodeToString().split("\n", limit = 5)
        if (parts.size != 5 || parts[0] != "v1") {
            com.fileapex.util.cancellableCatching { FriendsWire.writeFrame(stream, "0".encodeToByteArray()) }
            return
        }
        val friend = db().redeemInvite(parts[1], parts[2], parts[3], peer, parts[4])
        if (db().invite(parts[1])?.used == true) {
            inviteInternal.update { current -> current?.takeUnless { FriendInviteLink.parse(it.uri)?.inviteId == parts[1] } }
        }
        com.fileapex.util.cancellableCatching { FriendsWire.writeFrame(stream, (if (friend != null) "1" else "0").encodeToByteArray()) }
        if (friend != null) joinedInternal.tryEmit(friend.label)
    }

    /** Joins from a pasted or tapped invite. Returns the new friend's label. */
    suspend fun join(linkText: String, pin: String, myName: String): Result<String> = runCatching {
        val link = FriendInviteLink.parse(linkText) ?: throw IOException("That is not a friends invite")
        if (!Regex("\\d{6}").matches(pin)) throw IOException("The PIN is six digits")
        val ep = ensureEndpoint()
        val stream = ep.open(link.inviterKey, link.address)
        try {
            stream.write(byteArrayOf(KIND_PAIR.toByte()))
            FriendsWire.writeFrame(stream, "v1\n${link.inviteId}\n${link.secret}\n$pin\n${myName.take(40)}".encodeToByteArray())
            val reply = FriendsWire.readFrame(stream).decodeToString()
            if (reply != "1") throw IOException("The invite or PIN was not accepted")
        } finally { stream.abort() }
        val label = link.inviterName.ifBlank { "Friend" }.let { FriendsWire.safeName(it).take(40).ifBlank { "Friend" } }
        db().putLink(FriendLinkEntity(link.inviterKey, label, link.group, TimeUtils.now(), uniqueFriendFolder(label, link.inviterKey, links())))
        addedInternal.tryEmit(label)
        label
    }

    private fun friendDir(folder: String) = File(downloadsProvider(), folder)

    /** Local only: the other device never learns the new name. The folder follows it, files included. */
    suspend fun rename(friendKey: String, newLabel: String) = withContext(Dispatchers.IO) {
        friendChanges.withLock {
            val link = db().link(friendKey) ?: return@withLock
            val label = newLabel.trim().take(40).ifBlank { return@withLock }
            if (inboundJobs.values.any { it.peerId == friendKey } || board.transfers.value.any { it.friendKey == friendKey && !it.status.finished })
                throw IOException("Finish friend transfers before renaming")
            val folder = uniqueFriendFolder(label, friendKey, links())
            val from = friendDir(link.folderName)
            val to = friendDir(folder)
            val moved = from.exists() && link.folderName != folder
            if (moved && (to.exists() || !from.renameTo(to))) throw IOException("Could not rename friend folder")
            var saved = false
            try {
                db().putLink(link.copy(label = label, folderName = folder))
                saved = true
            } finally {
                if (moved && !saved) withContext(NonCancellable) {
                    if (db().link(friendKey)?.folderName != folder && !to.renameTo(from))
                        throw IOException("Could not restore friend folder after failed rename")
                }
            }
        }
    }

    suspend fun setAutoAccept(friendKey: String, autoAccept: Boolean) = db().setAutoAccept(friendKey, autoAccept)

    /** Forgets the friend and any unfinished partial files. Received files stay. */
    suspend fun remove(friendKey: String) = withContext(Dispatchers.IO) {
        friendChanges.withLock {
            val link = db().link(friendKey)
            batchGrants.revoke(friendKey)
            val ids = board.transfers.value.filter { it.friendKey == friendKey && !it.status.finished }.map { it.id }
            val parts = ids.mapNotNull { partFiles[it] }
            val queued = ids.mapNotNull { sendQueue.removeItem(it) }
            val jobs = ids.flatMap { id -> listOfNotNull(sendJobs[id], receiveJobs[id]) } + inboundJobs.filterValues { it.peerId == friendKey }.keys
            ids.forEach { id ->
                userCanceled.add(id)
                streams[id]?.abort()
                board.status(id, FriendTransferStatus.CANCELED)
            }
            inboundJobs.filterValues { it.peerId == friendKey }.values.forEach { it.stream.abort() }
            jobs.distinct().forEach { it.cancel() }
            jobs.distinct().forEach { it.join() }
            offers.declineFriend(friendKey)
            ids.forEach { sendJobs.remove(it); receiveJobs.remove(it); streams.remove(it); partFiles.remove(it); userCanceled.remove(it) }
            db().forgetFriend(friendKey)
            removalNotice.removed(friendKey) { endpoint?.takeIf { e -> e.isAlive }?.open(it, null) }
            if (friendKey in pinned.value) setPinned(friendKey, false)
            queued.filter { it.owned }.forEach { deleteOwnedFriendFile(it.file) }
            parts.forEach { if (it.exists() && !it.delete()) throw IOException("Could not remove friend partial file") }
            link?.let { friendDir(it.folderName).listFiles { f -> FriendReceiveFiles.isPartial(f.name) }?.forEach { f -> if (!f.delete()) throw IOException("Could not remove friend partial file") } }
        }
    }

    fun folderPath(link: FriendLinkEntity): String = friendDir(link.folderName).absolutePath

    private suspend fun receive(link: FriendLinkEntity, stream: IrohStream) {
        val dir = friendDir(link.folderName)
        val job = currentCoroutineContext()[Job]
        var id: String? = null
        var completed = false
        val receiver = FriendsReceiver(
            dir,
            FriendsLimits.maxBytes(settingsProvider().friendsMaxFileMb()),
            onEvent = { event ->
                when (event) {
                    is FriendReceiveEvent.Started -> {
                        val tid = "r:${link.friendKey}:${event.offer.transferId}"
                        id = tid
                        job?.let { receiveJobs[tid] = it }
                        streams[tid] = stream
                        partFiles[tid] = File(dir, event.partName)
                        board.put(FriendTransfer(tid, link.friendKey, link.label, FriendTransferDirection.RECEIVE, FriendsWire.safeName(event.offer.fileName), event.offer.sizeBytes, event.offset, FriendTransferStatus.CONNECTING))
                        board.status(tid, FriendTransferStatus.ACTIVE)
                    }
                    is FriendReceiveEvent.Progress -> id?.let { board.progress(it, event.received) }
                    is FriendReceiveEvent.Verifying -> id?.let { board.status(it, FriendTransferStatus.VERIFYING) }
                    is FriendReceiveEvent.Declined -> board.put(
                        FriendTransfer("d" + SecureRandom().nextLong().toString(16), link.friendKey, link.label, FriendTransferDirection.RECEIVE, FriendsWire.safeName(event.offer.fileName), event.offer.sizeBytes, 0L, FriendTransferStatus.REFUSED, note = event.reason)
                            .also { scope.launch { delay(FINISHED_KEEP_MS); board.drop(it.id) } }
                    )
                }
            }
        ) { offer -> ask(link, offer) }
        try {
            val result = receiver.handle(stream, link.friendKey)
            val tid = id
            val canceled = tid != null && tid in userCanceled
            if (canceled) partFiles[tid]?.delete()
            when {
                result is FriendReceiveResult.Received -> {
                    completed = true
                    tid?.let { board.status(it, FriendTransferStatus.DONE) }
                    FriendReceivedFile(link.label, result.file.name, result.file.absolutePath).let {
                        receivedInternal.tryEmit(it)
                        FriendsNotifier.fileReceived(it)
                    }
                    db().putIncoming(
                        FriendIncomingEntity(
                            "r" + SecureRandom().nextLong().toString(16), link.friendKey, result.file.name, result.file.length(),
                            FriendsReceiver.sha256(result.file), "RECEIVED", result.file.length(), result.file.absolutePath, TimeUtils.now()
                        )
                    )
                }
                tid != null && (canceled || result == FriendReceiveResult.CanceledBySender) -> board.status(tid, FriendTransferStatus.CANCELED)
                tid != null && result != FriendReceiveResult.Refused -> board.status(tid, FriendTransferStatus.FAILED, FriendFailure.LOST)
            }
        } catch (e: CancellationException) {
            id?.let { tid ->
                if (tid in userCanceled) partFiles[tid]?.delete()
                board.status(tid, FriendTransferStatus.CANCELED)
            }
            throw e
        } finally {
            id?.let { tid -> receiveJobs.remove(tid); streams.remove(tid); partFiles.remove(tid); userCanceled.remove(tid) }
            // A finished stream is left to close by itself: aborting it can drop the final ok frame the sender is waiting for.
            if (!completed) stream.abort()
        }
    }

    private suspend fun ask(link: FriendLinkEntity, offer: FriendOffer): Boolean {
        if (db().link(link.friendKey)?.autoAccept == true || batchGrants.allows(link.friendKey, offer)) return true
        return offers.ask(link, "f:${link.friendKey}:${offer.transferId}", FriendsWire.safeName(offer.fileName), offer.sizeBytes, 1)
    }

    /** One question for a whole batch; a yes opens a grant for the announced files only. */
    private suspend fun askBatch(link: FriendLinkEntity, batch: FriendBatch): Boolean {
        if (db().link(link.friendKey)?.autoAccept == true) return true
        val names = batch.firstNames.joinToString(", ") + if (batch.count > batch.firstNames.size) ", \u2026" else ""
        return offers.ask(link, "b:${link.friendKey}:${batch.id}", AppI18n.t("friends_batch_summary", batch.count, names), batch.totalBytes, batch.count)
    }

    fun decide(offerId: String, accept: Boolean, always: Boolean = false) = offers.decide(offerId, accept, always)

    /** Adds the files to the friend's queue; up to four go at once, one large file at a time, the first right away. */
    suspend fun sendPaths(friendKey: String, paths: List<String>) {
        val files = FriendSendIntake.accept(db().link(friendKey) != null, paths)
        if (files.isNotEmpty()) enqueueSend(friendKey, files)
    }
    /** For screens that close right after Send: these run on the hub's scope, so closing the screen cannot cancel a send before it is queued. */
    fun queueSend(friendKey: String, paths: List<String>) { scope.launch { sendPaths(friendKey, paths) } }
    fun queueFiles(friendKey: String, files: List<File>) { scope.launch { enqueueSend(friendKey, files) } }
    suspend fun enqueueSend(friendKey: String, files: List<File>, owned: Boolean = false) = withContext(Dispatchers.IO) {
        friendChanges.withLock {
            val link = db().link(friendKey)
            val accepted = FriendSendIntake.acceptFiles(link != null, files, available = available)
            if (owned) files.filterNot { it in accepted }.forEach(::deleteOwnedFriendFile)
            if (link == null) return@withLock false
            sendQueue.enqueue(friendKey, link.label, accepted, owned, board, scope) { openKind(friendKey, KIND_BATCH) }
            repeat(lanes.lanesToStart(friendKey)) { scope.launch { runSendWorker(friendKey) } }
            accepted.isNotEmpty()
        }
    }

    private suspend fun runSendWorker(friendKey: String) {
        while (true) {
            val item = lanes.next(friendKey) ?: return
            val job = scope.launch(start = CoroutineStart.LAZY) { sendOne(item) }
            job.invokeOnCompletion { item.batch?.release() }
            sendJobs[item.id] = job
            job.start()
            job.join()
            sendJobs.remove(item.id)
            lanes.finished(friendKey, item)
        }
    }

    /** The user pressed Cancel: a waiting file is dropped, an active send or receive is stopped and a received part file is deleted. */
    fun cancel(transferId: String) {
        if (board.get(transferId)?.status?.finished != false) return
        userCanceled.add(transferId)
        sendQueue.removeItem(transferId)?.let { dropped ->
            if (dropped.owned) scope.launch { deleteOwnedFriendFile(dropped.file) }
            board.status(transferId, FriendTransferStatus.CANCELED)
            userCanceled.remove(transferId)
            return
        }
        // A send stops at the next piece boundary and tells the receiver to delete its part file; the hard stop covers a send stuck waiting or on a dead link.
        sendJobs[transferId]?.let { job -> scope.launch { delay(SENDER_CANCEL_GRACE_MS); job.cancel(); streams[transferId]?.abort() } }
        receiveJobs[transferId]?.cancel()
        if (sendJobs[transferId] == null) streams[transferId]?.abort()
    }

    private suspend fun openKind(friendKey: String, kind: Int): IrohStream {
        val s = ensureEndpoint().open(friendKey, null)
        try { FriendsWire.writeRaw(s, byteArrayOf(kind.toByte())) } catch (e: IOException) { s.abort(); throw e }
        return s
    }

    private suspend fun sendOne(item: FriendsSendQueue.Item) = sendSlots.withPermit {
        val id = item.id
        var stream: IrohStream? = null; var reason = FriendFailure.LOST
        fun end(status: FriendTransferStatus, note: String = "") = board.status(id, if (id in userCanceled) FriendTransferStatus.CANCELED else status, note)
        try {
            if (id in userCanceled || board.get(id)?.status?.finished == true) return end(FriendTransferStatus.CANCELED)
            val link = db().link(item.friendKey) ?: return end(FriendTransferStatus.FAILED, FriendFailure.GONE)
            board.status(id, FriendTransferStatus.CONNECTING)
            val batchId = item.batch?.let { it.id { board.status(id, FriendTransferStatus.WAITING) } ?: return end(FriendTransferStatus.REFUSED) } ?: ""
            val opened = ensureEndpoint().open(link.friendKey, null)
            stream = opened
            streams[id] = opened
            FriendsWire.writeRaw(opened, byteArrayOf(KIND_FILE.toByte()))
            // The same file offered again gets the same id, so an interrupted send resumes.
            val transferId = sha256Hex("${item.file.absolutePath}|${item.file.length()}|${item.file.lastModified()}".encodeToByteArray()).take(32)
            val result = FriendsSender.send(
                opened, item.file, transferId,
                onPhase = { phase ->
                    when (phase) {
                        FriendSendPhase.Preparing -> board.status(id, FriendTransferStatus.PREPARING)
                        FriendSendPhase.Waiting -> board.status(id, FriendTransferStatus.WAITING)
                        is FriendSendPhase.Sending -> { board.status(id, FriendTransferStatus.ACTIVE); board.progress(id, phase.offset) }
                        FriendSendPhase.Verifying -> board.status(id, FriendTransferStatus.VERIFYING)
                    }
                },
                shouldCancel = { id in userCanceled },
                batchId = batchId, onFailure = { reason = it }
            ) { board.progress(id, it) }
            println("FriendsHub: a send to ${link.friendKey.take(4)}... ended as $result${if (result == FriendSendResult.Failed) " ($reason)" else ""}")
            end(when (result) { FriendSendResult.Done -> FriendTransferStatus.DONE; FriendSendResult.Refused -> FriendTransferStatus.REFUSED; FriendSendResult.Failed -> FriendTransferStatus.FAILED; FriendSendResult.Canceled -> FriendTransferStatus.CANCELED }, reason)
        } catch (e: CancellationException) {
            board.status(id, FriendTransferStatus.CANCELED)
            throw e
        } catch (e: Exception) {
            end(FriendTransferStatus.FAILED, FriendFailure.of(e).also { println("FriendsHub: a send failed ($it) :: ${e::class.simpleName} ${e.message?.replace(Regex("""\d+\.\d+\.\d+\.(\d+)"""), ".$1")?.take(80)}") })
        } finally {
            stream?.abort()
            streams.remove(id)
            userCanceled.remove(id)
            if (item.owned) deleteOwnedFriendFile(item.file)
        }
    }

    /** Copies the shared files once per friend into a private folder and queues them, so the share screen can clean up its own staging at once. */
    suspend fun enqueueShared(friendKeys: Collection<String>, files: List<File>) = enqueueSharedFriendFiles(friendKeys, files)

    /** Partial files from a failed or interrupted transfer are kept for a few days so it can resume, then removed. */
    private suspend fun purgeOldParts() =
        links().forEach { FriendReceiveFiles.deletePartialsOlderThan(friendDir(it.folderName), TimeUtils.now() - PART_KEEP_MS) }

    fun pathInfo(friendKey: String): FriendPathInfo? = endpoint?.pathInfo(friendKey)
    suspend fun connect(friendKey: String) = removalNotice.connect(friendKey) { ensureEndpoint() }

    private const val KIND_PAIR = 1
    private const val KIND_FILE = 2
    private const val KIND_BATCH = 3
    private const val MAX_UNKNOWN = 4
    private const val FINISHED_KEEP_MS = 60_000L
    private const val SENDER_CANCEL_GRACE_MS = 2_000L
    private const val PART_KEEP_MS = 3L * 24 * 60 * 60 * 1000
    private const val INVITE_LIFETIME_MS = 10 * 60 * 1000L
}
