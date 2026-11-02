package com.fileapex.friends

import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The path Iroh picked to a friend: [relay], or a direct one that is [local] (same network) or goes through the internet. */
class FriendPathInfo(val relay: Boolean, val local: Boolean, val rttMs: Long)

/** Tells a same-network address (private, link-local, loopback) from an internet one; [remote] is `ip:port` or `[ipv6]:port`. */
object FriendPathAddress {
    fun isLocal(remote: String): Boolean {
        val host = (if (remote.startsWith("[")) remote.substringAfter("[").substringBefore("]") else remote.substringBeforeLast(":")).substringBefore("%").lowercase()
        if (host.isEmpty()) return false
        if (":" in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || Regex("fe[89ab].*").matches(host)
        val p = host.split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }
}

/** How a friend is reached right now. [RELAY_NO_DIRECT] means the relay has carried the link for a while and no direct path came up. */
enum class FriendPath { CHECKING, DIRECT_LAN, DIRECT_INTERNET, RELAY, RELAY_NO_DIRECT, RELAY_HIDDEN, UNREACHABLE }

data class FriendPathStatus(val path: FriendPath, val rttMs: Long = 0L)

/**
 * Connects to each friend and reads the path Iroh picked. A relay path is given [directWaitMs] to upgrade to a direct one before
 * it is called a failure; with the IP address hidden the relay is the point, so it is never called one.
 */
class FriendPathTracker(
    private val infoOf: (String) -> FriendPathInfo?,
    private val connect: suspend (String) -> Unit,
    private val hideIp: () -> Boolean,
    private val now: () -> Long,
    private val directWaitMs: Long = 10_000L
) {
    private val internal = MutableStateFlow<Map<String, FriendPathStatus>>(emptyMap())
    val paths: StateFlow<Map<String, FriendPathStatus>> = internal.asStateFlow()
    private val relaySince = HashMap<String, Long>()
    private val noPathYet = HashSet<String>()

    suspend fun refresh(friendKeys: List<String>) {
        internal.update { before -> friendKeys.associateWith { before[it] ?: FriendPathStatus(FriendPath.CHECKING) } }
        val failed = coroutineScope {
            friendKeys.map { key ->
                async { key to (infoOf(key) == null && try { connect(key); false } catch (e: CancellationException) { throw e } catch (_: Exception) { true }) }
            }.awaitAll().filter { it.second }.map { it.first }.toSet()
        }
        val next = LinkedHashMap<String, FriendPathStatus>()
        friendKeys.forEach { key ->
            val info = infoOf(key)
            if (info != null) noPathYet.remove(key)
            if (info?.relay == true) relaySince.getOrPut(key) { now() } else relaySince.remove(key)
            val path = when {
                info == null -> if (key in failed || !noPathYet.add(key)) FriendPath.UNREACHABLE else FriendPath.CHECKING
                !info.relay -> if (info.local) FriendPath.DIRECT_LAN else FriendPath.DIRECT_INTERNET
                hideIp() -> FriendPath.RELAY_HIDDEN
                now() - relaySince.getValue(key) >= directWaitMs -> FriendPath.RELAY_NO_DIRECT
                else -> FriendPath.RELAY
            }
            next[key] = FriendPathStatus(path, info?.rttMs ?: 0L)
        }
        relaySince.keys.retainAll(friendKeys.toSet())
        noPathYet.retainAll(friendKeys.toSet())
        internal.value.let { before -> next.forEach { (key, status) -> if (before[key]?.path != status.path) println("FriendPaths: ${key.take(4)}... ${status.path} ${status.rttMs} ms") } }
        internal.value = next
    }
}

/** The tracker the Groups window uses. */
internal object FriendPaths {
    val tracker = FriendPathTracker(FriendsHub::pathInfo, FriendsHub::connect, { FileApexServices.settings.friendsHideIp() }, TimeUtils::now)
}
