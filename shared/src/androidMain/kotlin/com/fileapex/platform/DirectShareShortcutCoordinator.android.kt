package com.fileapex.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android Direct Share device shortcuts.
 *
 * Long-lived shortcuts need resource (or properly granted URI) icons.
 * Do not use [ShortcutInfoCompat.setExcludedFromSurfaces] with
 * [ShortcutInfoCompat.SURFACE_LAUNCHER] when publishing via
 * [ShortcutManagerCompat] — Compat strips those shortcuts before the
 * platform call, so the share sheet never sees device targets.
 */
object DirectShareShortcutCoordinator {
    const val CATEGORY_SHARE_TARGET = "com.fileapex.category.SHARE_TARGET"
    const val EXTRA_TARGET_DEVICE_ID = "com.fileapex.extra.TARGET_DEVICE_ID"
    /** Matches [android.content.pm.ShortcutManager.EXTRA_SHORTCUT_ID] on API 29+. */
    const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"

    private const val SHORTCUT_PREFIX = "share-device-"
    private const val RATE_LIMIT_RETRY_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var rateLimitRetryJob: Job? = null
    @Volatile
    private var pendingPublishPeers: List<PairedDeviceEntity>? = null
    private lateinit var appContext: Context

    fun start(context: Context) {
        if (observeJob?.isActive == true) return
        appContext = context.applicationContext
        observeJob = scope.launch {
            // Publish once DB roster is ready, then on roster / online-rank changes.
            FileApexServices.deviceRepository.observeDevices()
                .map { devices -> rankPeersForShare(devices) }
                .distinctUntilChanged { old, new ->
                    old.map { Triple(it.deviceId, it.deviceName, onlineRankKey(it)) } ==
                        new.map { Triple(it.deviceId, it.deviceName, onlineRankKey(it)) }
                }
                .collect { peers -> publishShortcuts(peers) }
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
            runCatching {
                ShortcutManagerCompat.pushDynamicShortcut(appContext, buildShortcut(peer, rank = 0))
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
        DirectShareUsageStore.clearShareCount(appContext, deviceId)
    }

    fun shortcutId(deviceId: String): String = "$SHORTCUT_PREFIX$deviceId"

    fun deviceIdFromShortcutId(shortcutId: String?): String? {
        if (shortcutId.isNullOrBlank() || !shortcutId.startsWith(SHORTCUT_PREFIX)) return null
        return shortcutId.removePrefix(SHORTCUT_PREFIX).takeIf { it.isNotBlank() }
    }

    /** Re-publish after peer discovery or roster changes outside the observe flow. */
    fun refreshFromPeerDiscovery() {
        if (!::appContext.isInitialized || !FileApexServices.isDatabaseReady()) return
        scope.launch {
            publishShortcuts(rankPeersForShare(FileApexServices.deviceRepository.listDevices()))
        }
    }

    private fun onlineRankKey(device: PairedDeviceEntity): Int =
        if (FileApexServices.presenceMonitor.isDeviceOnline(device)) 1 else 0

    private fun rankPeersForShare(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        val presence = FileApexServices.presenceMonitor
        return devices.sortedWith(
            compareByDescending<PairedDeviceEntity> {
                if (presence.isDeviceOnline(it)) 1 else 0
            }
                .thenByDescending {
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
        val peersToPublish = rankPeersForShare(peers).take(maxCount)
        val targetIds = peersToPublish.map { shortcutId(it.deviceId) }.toSet()

        val staleIds = ShortcutManagerCompat.getDynamicShortcuts(appContext)
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_PREFIX) && it !in targetIds }
        if (staleIds.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(appContext, staleIds)
        }

        if (peersToPublish.isEmpty()) {
            // Only clear when roster is truly empty — avoid wiping on a transient empty emit.
            val confirmedEmpty = FileApexServices.deviceRepository.listDevices().isEmpty()
            if (confirmedEmpty) {
                ShortcutManagerCompat.removeDynamicShortcuts(
                    appContext,
                    ShortcutManagerCompat.getDynamicShortcuts(appContext)
                        .map { it.id }
                        .filter { it.startsWith(SHORTCUT_PREFIX) }
                )
                println("DirectShareShortcutCoordinator: cleared share shortcuts (empty roster)")
            } else {
                println("DirectShareShortcutCoordinator: skip clear - empty publish list but roster not empty")
            }
            pendingPublishPeers = null
            return
        }

        val shortcuts = peersToPublish.mapIndexed { index, peer ->
            buildShortcut(peer, rank = index)
        }
        val ok = runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)
        }.getOrElse { error ->
            println("DirectShareShortcutCoordinator: setDynamicShortcuts threw - ${error.message}")
            false
        }
        val publishedIds = ShortcutManagerCompat.getDynamicShortcuts(appContext)
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_PREFIX) }
        if (!ok || publishedIds.isEmpty()) {
            println(
                "DirectShareShortcutCoordinator: publish failed " +
                    "(ok=$ok, system=${publishedIds.size}/${peersToPublish.size}) - retry later"
            )
            pendingPublishPeers = peersToPublish
            scheduleRateLimitRetry()
            return
        }

        pendingPublishPeers = null
        rateLimitRetryJob?.cancel()

        peersToPublish.take(SHARE_SHEET_VISIBLE_HINT).forEach { peer ->
            val usage = DirectShareUsageStore.shareCount(appContext, peer.deviceId)
            if (usage > 0) {
                ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId(peer.deviceId))
            }
        }

        println(
            "DirectShareShortcutCoordinator: published ${peersToPublish.size} share shortcut(s) " +
                "(system has ${publishedIds.size}): " +
                peersToPublish.joinToString { it.deviceName }
        )
    }

    private fun scheduleRateLimitRetry() {
        if (rateLimitRetryJob?.isActive == true) return
        rateLimitRetryJob = scope.launch {
            delay(RATE_LIMIT_RETRY_MS)
            if (!isActive) return@launch
            val pending = pendingPublishPeers ?: return@launch
            publishShortcuts(pending)
        }
    }

    private fun buildShortcut(peer: PairedDeviceEntity, rank: Int): ShortcutInfoCompat {
        // Launcher long-press uses this intent; Direct Share delivers ACTION_SEND to
        // MainActivity with EXTRA_SHORTCUT_ID from shortcuts.xml share-target.
        val launchIntent = Intent(Intent.ACTION_DEFAULT).apply {
            setClassName(appContext, MAIN_ACTIVITY_CLASS)
            putExtra(EXTRA_TARGET_DEVICE_ID, peer.deviceId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        return ShortcutInfoCompat.Builder(appContext, shortcutId(peer.deviceId))
            .setShortLabel(peer.deviceName.take(SHORT_LABEL_MAX))
            .setLongLabel("Send to ${peer.deviceName}".take(LONG_LABEL_MAX))
            .setIcon(shareTargetIcon())
            .setActivity(ComponentName(appContext.packageName, MAIN_ACTIVITY_CLASS))
            .setIntent(launchIntent)
            .setCategories(setOf(CATEGORY_SHARE_TARGET))
            .setPerson(person)
            .setLongLived(true)
            .setRank(rank)
            .build()
    }

    /** Resource icons are required for reliable long-lived Sharing Shortcuts. */
    private fun shareTargetIcon(): IconCompat =
        IconCompat.createWithResource(appContext, AndroidNotificationChannels.smallIcon)

    /**
     * Android typically renders up to four Direct Share bubbles in portrait; keep the
     * top ranks boosted via [ShortcutManagerCompat.reportShortcutUsed].
     */
    private const val SHARE_SHEET_VISIBLE_HINT = 4
    private const val SHORT_LABEL_MAX = 25
    private const val LONG_LABEL_MAX = 50

    private const val MAIN_ACTIVITY_CLASS = "com.fileapex.MainActivity"
}

fun initAndroidDirectShareShortcuts(context: Context) {
    DirectShareShortcutCoordinator.start(context)
}
