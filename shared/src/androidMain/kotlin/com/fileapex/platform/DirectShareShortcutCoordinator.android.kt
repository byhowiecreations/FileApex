package com.fileapex.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android Direct Share device shortcuts.
 *
 * Long-lived shortcuts need resource (or properly granted URI) icons.
 * Do not use [ShortcutInfoCompat.setExcludedFromSurfaces] with
 * [ShortcutInfoCompat.SURFACE_LAUNCHER] when publishing via
 * [ShortcutManagerCompat], Compat strips those shortcuts before the
 * platform call, so the share sheet never sees device targets.
 */
object DirectShareShortcutCoordinator {
    const val CATEGORY_SHARE_TARGET = "com.fileapex.category.SHARE_TARGET"
    const val EXTRA_TARGET_DEVICE_ID = "com.fileapex.extra.TARGET_DEVICE_ID"
    const val EXTRA_OPEN_BULLETIN = "com.fileapex.extra.OPEN_BULLETIN"
    /** Matches [android.content.pm.ShortcutManager.EXTRA_SHORTCUT_ID] on API 29+. */
    const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    const val BULLETIN_SHORTCUT_ID = "share-bulletin-board"
    const val OPEN_URI_SCHEME = "fileapex"
    const val OPEN_URI_HOST = "open"

    sealed class LauncherDestination {
        data object BulletinBoard : LauncherDestination()
        data class Device(val deviceId: String) : LauncherDestination()
    }

    private const val SHORTCUT_PREFIX = "share-device-"
    private const val FRIEND_PREFIX = "share-friend-"
    private const val MAX_FRIEND_SHORTCUTS = 3
    private const val RATE_LIMIT_RETRY_MS = 30_000L
    /** Order-only changes (a peer went on or offline) wait this long since the last publish. */
    private const val REORDER_MIN_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publishMutex = Mutex()
    private var observeJob: Job? = null
    private var rateLimitRetryJob: Job? = null
    @Volatile
    private var pendingPublishPeers: List<PairedDeviceEntity>? = null
    @Volatile
    private var lastPublishedSignature: String? = null
    @Volatile
    private var lastPublishedMembership: String? = null
    @Volatile
    private var lastPublishedAtMs = 0L
    private lateinit var appContext: Context

    fun start(context: Context) {
        if (observeJob?.isActive == true) return
        appContext = context.applicationContext
        observeJob = scope.launch {
            // Publish once DB roster is ready, then when the roster, a name or the usage order changes.
            // Online state is not part of the order: a shortcut cannot show it, and the target is
            // checked against the roster when it is picked.
            FileApexServices.deviceRepository.observeDevices()
                .map { devices -> rankPeersForShare(devices) }
                .distinctUntilChanged { old, new ->
                    old.map { it.deviceId to it.deviceName } == new.map { it.deviceId to it.deviceName }
                }
                .collect { peers -> publishShortcuts(peers) }
        }
        scope.launch {
            com.fileapex.friends.FriendsHub.friends
                .map { list -> list.map { it.friendKey to it.label } }
                .distinctUntilChanged()
                .collect { refreshFromPeerDiscovery() }
        }
        // Immediate pass after Application.onCreate once services are up.
        scope.launch {
            delay(750L)
            if (!FileApexServices.isDatabaseReady()) return@launch
            publishShortcuts(rankPeersForShare(FileApexServices.deviceRepository.listDevices()))
        }
    }

    fun recordTargetUsed(deviceId: String) {
        if (!::appContext.isInitialized || deviceId.isBlank()) return
        DirectShareUsageStore.recordShare(appContext, deviceId)
        ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId(deviceId))
        scope.launch {
            val peers = rankPeersForShare(FileApexServices.deviceRepository.listDevices())
            publishShortcuts(peers)
            val peer = peers.firstOrNull { it.deviceId == deviceId } ?: return@launch
            cancellableCatching {
                ShortcutManagerCompat.pushDynamicShortcut(appContext, buildShortcut(appContext, peer, rank = 0))
            }.onFailure { error ->
                println("DirectShareShortcutCoordinator: pushDynamicShortcut failed - ${error.message}")
            }
        }
    }

    fun purgeTarget(deviceId: String) {
        if (!::appContext.isInitialized || deviceId.isBlank()) return
        ShortcutManagerCompat.removeDynamicShortcuts(
            appContext,
            listOf(shortcutId(deviceId))
        )
        lastPublishedSignature = null
        lastPublishedMembership = null
        DirectShareUsageStore.clearShareCount(appContext, deviceId)
    }

    fun shortcutId(deviceId: String): String = "$SHORTCUT_PREFIX$deviceId"

    fun deviceIdFromShortcutId(shortcutId: String?): String? {
        if (shortcutId.isNullOrBlank() || shortcutId == BULLETIN_SHORTCUT_ID) return null
        if (!shortcutId.startsWith(SHORTCUT_PREFIX)) return null
        return shortcutId.removePrefix(SHORTCUT_PREFIX).takeIf { it.isNotBlank() }
    }

    fun friendKeyFromShortcutId(shortcutId: String?): String? =
        shortcutId?.takeIf { it.startsWith(FRIEND_PREFIX) }?.removePrefix(FRIEND_PREFIX)?.takeIf { it.isNotBlank() }

    private fun buildFriendShortcut(
        context: Context,
        friend: com.fileapex.friends.FriendShareTarget,
        rank: Int
    ): ShortcutInfoCompat {
        val person = Person.Builder().setName(friend.label).setKey(FRIEND_PREFIX + friend.key).setBot(false).setImportant(true).build()
        return ShortcutInfoCompat.Builder(context, FRIEND_PREFIX + friend.key)
            .setShortLabel(ShortcutLabel.of(friend.label, com.fileapex.i18n.AppI18n.t("paired_device"), SHORT_LABEL_MAX))
            .setLongLabel(com.fileapex.i18n.AppI18n.t("send_to_device", friend.label).take(LONG_LABEL_MAX))
            .setIcon(ShortcutIconRenderer.friend(context, friend.label, friend.group))
            .setActivity(ComponentName(context.packageName, MAIN_ACTIVITY_CLASS))
            .setIntent(Intent(Intent.ACTION_MAIN).setClassName(context, MAIN_ACTIVITY_CLASS))
            .setCategories(setOf(CATEGORY_SHARE_TARGET))
            .setPerson(person)
            .setLongLived(true)
            .setRank(rank)
            .build()
    }

    fun isBulletinShortcut(shortcutId: String?): Boolean = shortcutId == BULLETIN_SHORTCUT_ID

    fun bulletinOpenUri(): Uri = Uri.Builder()
        .scheme(OPEN_URI_SCHEME)
        .authority(OPEN_URI_HOST)
        .appendPath("bulletin")
        .build()

    fun deviceOpenUri(deviceId: String): Uri = Uri.Builder()
        .scheme(OPEN_URI_SCHEME)
        .authority(OPEN_URI_HOST)
        .appendPath("device")
        .appendPath(deviceId)
        .build()

    fun parseOpenUri(uri: Uri?): LauncherDestination? {
        if (uri == null) return null
        if (uri.scheme?.lowercase() != OPEN_URI_SCHEME) return null
        if (uri.host?.lowercase() != OPEN_URI_HOST) return null
        val segments = uri.pathSegments
        return when (segments.firstOrNull()?.lowercase()) {
            "bulletin" -> LauncherDestination.BulletinBoard
            "device" -> {
                val deviceId = segments.getOrNull(1)?.trim().orEmpty()
                if (deviceId.isEmpty()) null else LauncherDestination.Device(deviceId)
            }
            else -> null
        }
    }

    /**
     * Launcher long-press only. Share-sheet Direct Share stays on ACTION_SEND +
     * [EXTRA_SHORTCUT_ID] and must not be parsed here.
     */
    fun parseLauncherDestination(intent: Intent?): LauncherDestination? {
        if (intent == null || AndroidShareIntake.isShareAction(intent)) return null
        parseOpenUri(intent.data)?.let { return it }
        if (intent.getBooleanExtra(EXTRA_OPEN_BULLETIN, false)) {
            return LauncherDestination.BulletinBoard
        }
        val deviceId = intent.getStringExtra(EXTRA_TARGET_DEVICE_ID)?.trim().orEmpty()
        return if (deviceId.isEmpty()) null else LauncherDestination.Device(deviceId)
    }

    /** Re-publish after peer discovery or roster changes outside the observe flow. */
    fun refreshFromPeerDiscovery() {
        if (!::appContext.isInitialized || !FileApexServices.isDatabaseReady()) return
        scope.launch {
            publishShortcuts(rankPeersForShare(FileApexServices.deviceRepository.listDevices()))
        }
    }

    private fun rankPeersForShare(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        return devices.sortedWith(
            compareByDescending<PairedDeviceEntity> {
                DirectShareUsageStore.shareCount(appContext, it.deviceId)
            }
                .thenByDescending { if (isMacLike(it.deviceName)) 1 else 0 }
                .thenBy { it.deviceName.lowercase() }
        )
    }

    private fun isMacLike(name: String): Boolean {
        val lower = name.lowercase()
        return "macbook" in lower || "mac " in lower || lower.startsWith("mac")
    }

    private suspend fun publishShortcuts(peers: List<PairedDeviceEntity>) {
        if (!::appContext.isInitialized) return
        publishMutex.withLock {
            publishShortcutsLocked(peers)
        }
    }

    private suspend fun publishShortcutsLocked(peers: List<PairedDeviceEntity>) {
        if (!FileApexServices.isDatabaseReady()) {
            println("DirectShareShortcutCoordinator: skip publish - database not ready")
            return
        }
        if (ShortcutManagerCompat.isRateLimitingActive(appContext)) {
            println("DirectShareShortcutCoordinator: rate limited - scheduling retry")
            pendingPublishPeers = peers
            scheduleRateLimitRetry()
            return
        }

        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext)
            .coerceAtLeast(1)
        val friendsToPublish = com.fileapex.friends.FriendsHub.shareTargets(minOf(MAX_FRIEND_SHORTCUTS, (maxCount - 1).coerceAtLeast(0)))
        val peerSlots = (maxCount - 1 - friendsToPublish.size).coerceAtLeast(0)
        val peersToPublish = rankPeersForShare(peers).take(peerSlots)
        val friendSignature = friendsToPublish.joinToString(separator = "|") { "${it.key}\u0000${it.label}\u0000${it.group}" }
        val signature = peersToPublish.joinToString(separator = "|") { peer ->
            "${peer.deviceId}\u0000${peer.deviceName}"
        } + "#" + friendSignature
        if (signature == lastPublishedSignature) {
            return
        }
        val membership = peersToPublish
            .map { "${it.deviceId}\u0000${it.deviceName}" }
            .sorted()
            .joinToString(separator = "|") + "#" + friendSignature
        val sinceLastPublishMs = System.currentTimeMillis() - lastPublishedAtMs
        if (membership == lastPublishedMembership && sinceLastPublishMs < REORDER_MIN_INTERVAL_MS) {
            pendingPublishPeers = peers
            scheduleRateLimitRetry(REORDER_MIN_INTERVAL_MS - sinceLastPublishMs)
            return
        }
        val shortcuts = buildShortcutsForPublish(
            context = appContext,
            peersToPublish = peersToPublish,
            friendsToPublish = friendsToPublish
        )
        val expectedPeerCount = shortcuts.count { it.id.startsWith(SHORTCUT_PREFIX) }
        val targetIds = shortcuts.map { it.id }.toSet()

        val staleIds = ShortcutManagerCompat.getDynamicShortcuts(appContext)
            .map { it.id }
            .filter { (it.startsWith(SHORTCUT_PREFIX) || it.startsWith(FRIEND_PREFIX)) && it !in targetIds }
        if (staleIds.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(appContext, staleIds)
        }

        val ok = cancellableCatching {
            ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)
        }.getOrElse { error ->
            println("DirectShareShortcutCoordinator: setDynamicShortcuts threw - ${error.message}")
            false
        }
        val publishedIds = ShortcutManagerCompat.getDynamicShortcuts(appContext).map { it.id }
        val hasBulletin = BULLETIN_SHORTCUT_ID in publishedIds
        val deviceCount = publishedIds.count { it.startsWith(SHORTCUT_PREFIX) }
        if (!isPublishComplete(ok = ok, publishedIds = publishedIds, expectedPeerCount = expectedPeerCount)) {
            println(
                "DirectShareShortcutCoordinator: publish failed " +
                    "(ok=$ok, bulletin=$hasBulletin, devices=$deviceCount/$expectedPeerCount) - retry later"
            )
            pendingPublishPeers = peersToPublish
            scheduleRateLimitRetry()
            return
        }

        pendingPublishPeers = null
        rateLimitRetryJob?.cancel()
        markPublished(signature, membership)

        peersToPublish.take(SHARE_SHEET_VISIBLE_HINT).forEach { peer ->
            val usage = DirectShareUsageStore.shareCount(appContext, peer.deviceId)
            if (usage > 0) {
                ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId(peer.deviceId))
            }
        }

        println(
            "DirectShareShortcutCoordinator: published $expectedPeerCount device shortcut(s) + Bulletin Board " +
                "(system bulletin=$hasBulletin, devices=$deviceCount): " +
                peersToPublish.joinToString { it.deviceName }
        )
    }

    internal fun buildPublishShortcutsForTest(
        context: Context,
        peers: List<PairedDeviceEntity>,
        friends: List<com.fileapex.friends.FriendShareTarget>
    ): List<ShortcutInfoCompat> = buildShortcutsForPublish(
        context = context.applicationContext,
        peersToPublish = peers,
        friendsToPublish = friends
    )

    internal fun buildShortcutForTest(
        context: Context,
        peer: PairedDeviceEntity,
        rank: Int
    ): ShortcutInfoCompat = buildShortcut(context.applicationContext, peer, rank)

    internal fun isPublishComplete(
        ok: Boolean,
        publishedIds: List<String>,
        expectedPeerCount: Int
    ): Boolean {
        if (!ok || BULLETIN_SHORTCUT_ID !in publishedIds) return false
        return expectedPeerCount == 0 || publishedIds.any { it.startsWith(SHORTCUT_PREFIX) }
    }

    internal fun buildPeerShortcutsSafely(
        peers: List<PairedDeviceEntity>,
        builder: (PairedDeviceEntity, Int) -> ShortcutInfoCompat
    ): List<ShortcutInfoCompat> {
        val result = ArrayList<ShortcutInfoCompat>(peers.size)
        for (peer in peers) {
            cancellableCatching {
                builder(peer, result.size)
            }.onSuccess { shortcut ->
                result += shortcut
            }.onFailure { error ->
                println("DirectShareShortcutCoordinator: skipped peer shortcut - ${error.message}")
            }
        }
        return result
    }

    private fun buildShortcutsForPublish(
        context: Context,
        peersToPublish: List<PairedDeviceEntity>,
        friendsToPublish: List<com.fileapex.friends.FriendShareTarget>
    ): List<ShortcutInfoCompat> {
        val peerShortcuts = buildPeerShortcutsSafely(peersToPublish) { peer, rank ->
            buildShortcut(context, peer, rank)
        }
        val bulletinShortcut = buildBulletinBoardShortcut(context, rank = peerShortcuts.size)
        val friendShortcuts = friendsToPublish.mapIndexedNotNull { index, friend ->
            cancellableCatching {
                buildFriendShortcut(context, friend, rank = peerShortcuts.size + 1 + index)
            }.onFailure { error ->
                println("DirectShareShortcutCoordinator: skipped friend shortcut - ${error.message}")
            }.getOrNull()
        }
        return peerShortcuts + bulletinShortcut + friendShortcuts
    }

    private fun buildBulletinBoardShortcut(context: Context, rank: Int): ShortcutInfoCompat {
        val launchIntent = launcherOpenIntent(context, bulletinOpenUri()) {
            putExtra(EXTRA_OPEN_BULLETIN, true)
        }
        val person = Person.Builder()
            .setName(com.fileapex.i18n.AppI18n.t("bulletin_board"))
            .setKey(BULLETIN_SHORTCUT_ID)
            .setBot(false)
            .setImportant(true)
            .build()
        return ShortcutInfoCompat.Builder(context, BULLETIN_SHORTCUT_ID)
            .setShortLabel(com.fileapex.i18n.AppI18n.t("bulletin_board"))
            .setLongLabel(com.fileapex.i18n.AppI18n.t("post_to_bulletin_board"))
            .setIcon(ShortcutIconRenderer.bulletinBoard(context))
            .setActivity(ComponentName(context.packageName, MAIN_ACTIVITY_CLASS))
            .setIntent(launchIntent)
            .setCategories(setOf(CATEGORY_SHARE_TARGET))
            .setPerson(person)
            .setLongLived(true)
            .setRank(rank)
            .build()
    }

    private fun markPublished(signature: String, membership: String) {
        lastPublishedSignature = signature
        lastPublishedMembership = membership
        lastPublishedAtMs = System.currentTimeMillis()
    }

    private fun scheduleRateLimitRetry(delayMs: Long = RATE_LIMIT_RETRY_MS) {
        if (rateLimitRetryJob?.isActive == true) return
        rateLimitRetryJob = scope.launch {
            delay(delayMs)
            if (!isActive) return@launch
            val pending = pendingPublishPeers ?: return@launch
            publishShortcuts(pending)
        }
    }

    private fun launcherOpenIntent(context: Context, uri: Uri, extras: Intent.() -> Unit): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setClassName(context, MAIN_ACTIVITY_CLASS)
            data = uri
            extras()
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private fun buildShortcut(context: Context, peer: PairedDeviceEntity, rank: Int): ShortcutInfoCompat {
        // Launcher long-press uses this VIEW + fileapex://open/… intent.
        // Direct Share still starts ACTION_SEND with EXTRA_SHORTCUT_ID from
        // shortcuts.xml, it does not use setIntent().
        val launchIntent = launcherOpenIntent(context, deviceOpenUri(peer.deviceId)) {
            putExtra(EXTRA_TARGET_DEVICE_ID, peer.deviceId)
        }
        val person = Person.Builder()
            .setName(peer.deviceName)
            .setKey(peer.deviceId)
            .setBot(false)
            .setImportant(true)
            .build()
        // Do NOT call setExcludedFromSurfaces(SURFACE_LAUNCHER): ShortcutManagerCompat
        // silently drops those shortcuts from setDynamicShortcuts / addDynamicShortcuts,
        // so the platform stores nothing (share sheet then shows only "FileApex").
        return ShortcutInfoCompat.Builder(context, shortcutId(peer.deviceId))
            .setShortLabel(ShortcutLabel.of(peer.deviceName, com.fileapex.i18n.AppI18n.t("paired_device"), SHORT_LABEL_MAX))
            .setLongLabel(com.fileapex.i18n.AppI18n.t("send_to_device", peer.deviceName).take(LONG_LABEL_MAX))
            .setIcon(ShortcutIconRenderer.device(context, peer))
            .setActivity(ComponentName(context.packageName, MAIN_ACTIVITY_CLASS))
            .setIntent(launchIntent)
            .setCategories(setOf(CATEGORY_SHARE_TARGET))
            .setPerson(person)
            .setLongLived(true)
            .setRank(rank)
            .build()
    }

    private const val SHARE_SHEET_VISIBLE_HINT = 4
    private const val SHORT_LABEL_MAX = 25
    private const val LONG_LABEL_MAX = 50

    private const val MAIN_ACTIVITY_CLASS = "com.fileapex.MainActivity"
}

fun initAndroidDirectShareShortcuts(context: Context) {
    DirectShareShortcutCoordinator.start(context)
}
