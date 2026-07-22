package com.omninode.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.omninode.data.db.PairedDeviceEntity
import com.omninode.di.OmniNodeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single source of truth for Android Direct Share device shortcuts.
 */
object DirectShareShortcutCoordinator {
    const val CATEGORY_SHARE_TARGET = "com.omninode.category.SHARE_TARGET"
    const val EXTRA_TARGET_DEVICE_ID = "com.omninode.extra.TARGET_DEVICE_ID"

    private const val SHORTCUT_PREFIX = "share-device-"
    private const val CAPABILITY_SEND = "actions.intent.SEND"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private lateinit var appContext: Context

    fun start(context: Context) {
        if (observeJob?.isActive == true) return
        appContext = context.applicationContext
        observeJob = scope.launch {
            combine(
                OmniNodeServices.presenceMonitor.reachabilityEpochMs,
                OmniNodeServices.presenceMonitor.onlineDeviceIds,
                OmniNodeServices.presenceMonitor.onlineSnapshotEpochMs,
                OmniNodeServices.deviceRepository.observeDevices()
            ) { _, _, _, devices ->
                rankOnlinePeers(
                    devices.filter { OmniNodeServices.presenceMonitor.isDeviceOnline(it) }
                )
            }
                .collect { onlinePeers -> publishShortcuts(onlinePeers) }
        }
    }

    fun recordTargetUsed(deviceId: String) {
        if (!::appContext.isInitialized || deviceId.isBlank()) return
        DirectShareUsageStore.recordShare(appContext, deviceId)
        ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId(deviceId))
        scope.launch {
            val peers = rankOnlinePeers(
                OmniNodeServices.deviceRepository.listDevices()
                    .filter { OmniNodeServices.presenceMonitor.isDeviceOnline(it) }
            )
            publishShortcuts(peers)
            ShortcutManagerCompat.pushDynamicShortcut(
                appContext,
                buildShortcut(
                    peer = peers.firstOrNull { it.deviceId == deviceId }
                        ?: return@launch,
                    rank = 0
                )
            )
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

    private fun rankOnlinePeers(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> =
        devices.sortedWith(
            compareByDescending<PairedDeviceEntity> {
                DirectShareUsageStore.shareCount(appContext, it.deviceId)
            }
                .thenByDescending { if (isMacLike(it.deviceName)) 1 else 0 }
                .thenBy { it.deviceName.lowercase() }
        )

    private fun isMacLike(name: String): Boolean {
        val lower = name.lowercase()
        return "macbook" in lower || "mac " in lower || lower.startsWith("mac")
    }

    private fun publishShortcuts(onlinePeers: List<PairedDeviceEntity>) {
        if (!::appContext.isInitialized) return
        if (ShortcutManagerCompat.isRateLimitingActive(appContext)) {
            println("DirectShareShortcutCoordinator: rate limited — deferring shortcut publish")
            return
        }

        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext)
            .coerceAtLeast(1)
        val peersToPublish = rankOnlinePeers(onlinePeers).take(maxCount)
        val targetIds = peersToPublish.map { shortcutId(it.deviceId) }.toSet()

        val staleIds = ShortcutManagerCompat.getDynamicShortcuts(appContext)
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_PREFIX) && it !in targetIds }
        if (staleIds.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(appContext, staleIds)
        }

        if (peersToPublish.isEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(
                appContext,
                ShortcutManagerCompat.getDynamicShortcuts(appContext)
                    .map { it.id }
                    .filter { it.startsWith(SHORTCUT_PREFIX) }
            )
            return
        }

        val shortcuts = peersToPublish.mapIndexed { index, peer ->
            buildShortcut(peer, rank = index)
        }
        ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)

        peersToPublish.take(SHARE_SHEET_VISIBLE_HINT).forEach { peer ->
            val usage = DirectShareUsageStore.shareCount(appContext, peer.deviceId)
            if (usage > 0) {
                ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId(peer.deviceId))
            }
        }

        println(
            "DirectShareShortcutCoordinator: published ${peersToPublish.size} share shortcut(s) " +
                peersToPublish.joinToString { "${it.deviceName}(rank=${peersToPublish.indexOf(it)})" }
        )
    }

    private fun buildShortcut(peer: PairedDeviceEntity, rank: Int): ShortcutInfoCompat {
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(appContext.packageName, MAIN_ACTIVITY_CLASS)
            type = "*/*"
            putExtra(EXTRA_TARGET_DEVICE_ID, peer.deviceId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return ShortcutInfoCompat.Builder(appContext, shortcutId(peer.deviceId))
            .setShortLabel(peer.deviceName)
            .setLongLabel("Send to ${peer.deviceName}")
            .setIcon(deviceIcon(peer.deviceName))
            .setIntent(launchIntent)
            .setCategories(setOf(CATEGORY_SHARE_TARGET))
            .addCapabilityBinding(CAPABILITY_SEND)
            .addCapabilityBinding(CAPABILITY_SEND, "mimeType", listOf("*/*"))
            .setRank(rank)
            .setLongLived(true)
            .setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
            .build()
    }

    private fun deviceIcon(deviceName: String): IconCompat {
        val label = deviceName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "O"
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMacLike(deviceName)) {
                Color.parseColor("#546E7A")
            } else {
                Color.parseColor("#00897B")
            }
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.45f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        text.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(
            label,
            size / 2f,
            size / 2f - bounds.exactCenterY(),
            text
        )
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /**
     * Android typically renders up to four Direct Share bubbles in portrait; keep the
     * top ranks boosted via [ShortcutManagerCompat.reportShortcutUsed].
     */
    private const val SHARE_SHEET_VISIBLE_HINT = 4

    private const val MAIN_ACTIVITY_CLASS = "com.omninode.MainActivity"
}

fun initAndroidDirectShareShortcuts(context: Context) {
    DirectShareShortcutCoordinator.start(context)
}
