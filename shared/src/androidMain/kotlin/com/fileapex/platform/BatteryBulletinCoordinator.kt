package com.fileapex.platform

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Posts a Bulletin Board alert on [android.content.Intent.ACTION_BATTERY_LOW] and retracts it
 * cluster-wide once the device is charging.
 *
 * Motorola / some Honor units put the process in a stopped-like state and withhold
 * [android.content.Intent.ACTION_BATTERY_LOW] even though it is an implicit-broadcast exemption.
 * [onProcessStart] and the keep-alive job read [BatteryManager] and post if already at or
 * below 15% for this discharge cycle.
 *
 * Does not register for [android.content.Intent.ACTION_BATTERY_CHANGED] — that would wake the
 * process on every 1% tick. Sticky battery state is read once with
 * `registerReceiver(null, …)` only to fill in the percent / charging check.
 */
object BatteryBulletinCoordinator {
    private const val TAG = "BatteryBulletin"
    private const val PREFS_NAME = "fileapex_battery_bulletin"
    private const val KEY_ALERTED_THIS_CYCLE = "alerted_this_discharge_cycle"
    private const val KEY_LAST_ALERTED_LEVEL = "last_alerted_level_percent"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var dynamicReceiver: BatteryBulletinReceiver? = null

    @Volatile
    private var postInFlight = false

    data class Snapshot(
        val levelPercent: Int?,
        val charging: Boolean
    )

    fun onProcessStart(context: Context, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        registerDynamicReceiver(appContext)
        ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(appContext)
        reconcile(appContext, onComplete)
    }

    fun onBatteryLow(context: Context, onComplete: (() -> Unit)? = null) {
        onProcessStart(context, onComplete)
    }

    fun onCharging(context: Context, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                retractIfNeeded(appContext, reason = "device charging")
                val snapshot = currentSnapshot(appContext)
                if (BatteryBulletinPolicy.shouldClearAlertedCycle(snapshot.charging, snapshot.levelPercent)) {
                    clearAlertedCycle(appContext)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to retract low battery bulletin :: ${error.message}")
            }
            onComplete?.invoke()
        }
    }

    fun onUnplugged(context: Context, onComplete: (() -> Unit)? = null) {
        onProcessStart(context, onComplete)
    }

    fun currentSnapshot(context: Context): Snapshot {
        val intent = stickyBatteryIntent(context)
        if (intent == null) return Snapshot(levelPercent = null, charging = false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level < 0 || scale <= 0) {
            null
        } else {
            ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        }
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Snapshot(levelPercent = percent, charging = charging)
    }

    fun isAlertedThisCycle(context: Context): Boolean {
        synchronized(lock) {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALERTED_THIS_CYCLE, false)
        }
    }

    private fun reconcile(context: Context, onComplete: (() -> Unit)?) {
        scope.launch {
            runCatching {
                if (!FileApexAndroidBootstrap.ensureInitialized(context)) {
                    Log.w(TAG, "Skip battery bulletin reconcile - process not initialized")
                    return@runCatching
                }
                val snapshot = currentSnapshot(context)
                if (snapshot.charging) {
                    retractIfNeeded(context, reason = "launch reconcile charging")
                    if (BatteryBulletinPolicy.shouldClearAlertedCycle(true, snapshot.levelPercent)) {
                        clearAlertedCycle(context)
                    }
                    return@runCatching
                }
                if (!BatteryBulletinPolicy.shouldPostAlert(
                        levelPercent = snapshot.levelPercent,
                        charging = false,
                        alreadyAlertedThisCycle = isAlertedThisCycle(context)
                    )
                ) {
                    return@runCatching
                }
                postLowBatteryBulletin(context, snapshot.levelPercent)
            }.onFailure { error ->
                Log.w(TAG, "Battery bulletin reconcile failed :: ${error.message}")
            }
            onComplete?.invoke()
        }
    }

    private suspend fun postLowBatteryBulletin(context: Context, knownLevel: Int?) {
        synchronized(lock) {
            if (postInFlight || isAlertedThisCycleLocked(context)) {
                Log.i(TAG, "Low battery bulletin already active - skipping duplicate")
                return
            }
            postInFlight = true
        }
        try {
            if (isDevicePluggedIn(context)) {
                Log.i(TAG, "Device already charging - skipping low battery bulletin")
                return
            }
            val level = knownLevel ?: readBatteryLevelPercent(context)
            val note = FileApexServices.noteRepository.sendBatteryAlert(level)
            if (isDevicePluggedIn(context)) {
                retractIfNeeded(context, reason = "charging during post")
                Log.i(TAG, "Retracted low battery bulletin immediately (charging during post)")
            } else {
                markAlerted(context, level)
                Log.i(TAG, "Posted low battery bulletin noteId=${note.noteId} level=${level ?: "unknown"}")
            }
        } finally {
            synchronized(lock) { postInFlight = false }
        }
    }

    private suspend fun retractIfNeeded(context: Context, reason: String) {
        val selfId = runCatching { loadLocalIdentity().deviceId }.getOrDefault("")
        if (selfId.isEmpty()) return
        FileApexServices.noteRepository.retractBulletinsByKind(selfId, BulletinContentType.BATTERY_LOW)
        Log.i(TAG, "Retracted low battery bulletins for $selfId ($reason)")
    }

    private fun registerDynamicReceiver(context: Context) {
        if (receiverRegistered) return
        val receiver = BatteryBulletinReceiver()
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_BATTERY_LOW)
                    addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                    addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            dynamicReceiver = receiver
            receiverRegistered = true
            Log.i(TAG, "Registered dynamic battery bulletin receiver (LOW + POWER_CONNECTED/DISCONNECTED)")
        }.onFailure { error ->
            Log.w(TAG, "Dynamic battery bulletin receiver registration failed :: ${error.message}")
        }
    }

    /** One-shot sticky read — not a listener. */
    private fun stickyBatteryIntent(context: Context) =
        context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))

    private fun readBatteryLevelPercent(context: Context): Int? = currentSnapshot(context).levelPercent

    private fun isDevicePluggedIn(context: Context): Boolean = currentSnapshot(context).charging

    private fun isAlertedThisCycleLocked(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALERTED_THIS_CYCLE, false)
    }

    private fun markAlerted(context: Context, level: Int?) {
        synchronized(lock) {
            val editor = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALERTED_THIS_CYCLE, true)
            if (level != null) {
                editor.putInt(KEY_LAST_ALERTED_LEVEL, level)
            }
            editor.apply()
        }
    }

    private fun clearAlertedCycle(context: Context) {
        synchronized(lock) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALERTED_THIS_CYCLE, false)
                .remove(KEY_LAST_ALERTED_LEVEL)
                .apply()
        }
    }
}
