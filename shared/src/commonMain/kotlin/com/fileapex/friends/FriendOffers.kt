package com.fileapex.friends

import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.data.FriendsDao
import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FriendOffers(private val scope: CoroutineScope, private val dao: () -> FriendsDao, private val changes: Mutex) {
    private val pendingInternal = MutableStateFlow<List<PendingFriendOffer>>(emptyList())
    private val deciders = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    val pendingOffers = pendingInternal.asStateFlow()

    suspend fun ask(link: FriendLinkEntity, id: String, name: String, sizeBytes: Long, fileCount: Int): Boolean {
        val decision = CompletableDeferred<Boolean>()
        if (deciders.putIfAbsent(id, decision) != null) return false
        val pending = PendingFriendOffer(id, link.label, name, sizeBytes, TimeUtils.now(), link.friendKey, fileCount)
        pendingInternal.update { it + pending }
        try {
            FriendsNotifier.offerPending(pending)
            return decision.await()
        } finally {
            deciders.remove(id, decision)
            decision.cancel()
            pendingInternal.update { list -> list.filterNot { it.id == id } }
            FriendsNotifier.offerResolved(id)
        }
    }

    fun declineFriend(friendKey: String) {
        pendingInternal.value.filter { it.friendKey == friendKey }.forEach { offer ->
            deciders.remove(offer.id)?.complete(false)
            FriendsNotifier.offerResolved(offer.id)
        }
        pendingInternal.update { list -> list.filterNot { it.friendKey == friendKey } }
    }

    fun decide(offerId: String, accept: Boolean, always: Boolean) {
        val decision = deciders.remove(offerId) ?: return
        if (!accept || !always) { decision.complete(accept); return }
        val key = pendingInternal.value.firstOrNull { it.id == offerId }?.friendKey
        if (key == null) { decision.complete(false); return }
        scope.launch {
            try {
                val accepted = changes.withLock {
                    if (!decision.isActive || dao().link(key) == null) false
                    else { dao().setAutoAccept(key, true); true }
                }
                decision.complete(accepted)
            } catch (e: CancellationException) {
                decision.cancel()
                throw e
            } catch (e: Exception) {
                println("FriendOffers: acceptance setting failed :: ${e::class.simpleName}")
                decision.complete(false)
            }
        }
    }
}
