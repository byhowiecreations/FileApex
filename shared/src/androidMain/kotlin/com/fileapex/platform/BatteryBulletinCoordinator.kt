package com.fileapex.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Posts a Bulletin Board alert when this device hits [Intent.ACTION_BATTERY_LOW] and retracts it
 * cluster-wide once the device reports charging (plugged in).
 */
object BatteryBulletinCoordinator {
    private const val TAG = "BatteryBulletin"
    private const val PREFS_NAME = "fileapex_battery_bulletin"
    private const val KEY_ACTIVE_NOTE_ID = "active_low_battery_note_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var dynamicReceiver: BatteryBulletinReceiver? = null

    fun registerIfNeeded(context: Context) {
        val appContext = context.applicationContext
        registerDynamicReceiver(appContext)
        reconcileOnLaunch(appContext)
    }

    fun onBatteryLow(context: Context, onComplete: (() -> Unit)? = null) {
        scope.launch {
            runCatching {
                if (!FileApexAndroidBootstrap.ensureInitialized(context.applicationContext)) return@runCatching
                if (readActiveNoteId(context) != null) {
                    Log.i(TAG, "Low battery bulletin already active - skipping duplicate")
                    return@runCatching
                }
                if (isDevicePluggedIn(context)) {
                    Log.i(TAG, "Device already charging - skipping low battery bulletin")
                    return@runCatching
                }
                val level = readBatteryLevelPercent(context) ?: run {
                    Log.w(TAG, "Low battery broadcast received but level unavailable")
                    return@runCatching
                }
                val deviceName = LocalDeviceNameStore.current()
                    .ifBlank { loadLocalIdentity().deviceName }
                    .ifBlank { "This device" }
                val message = "The battery level is $level on $deviceName"
                val note = FileApexServices.noteRepository.sendNote(content = message)
                if (isDevicePluggedIn(context)) {
                    FileApexServices.noteRepository.deleteNoteFromAllDevices(note.noteId)
                    Log.i(TAG, "Retracted low battery bulletin immediately (charging during post)")
                } else {
                    setActiveNoteId(context, note.noteId)
                    Log.i(TAG, "Posted low battery bulletin noteId=${note.noteId} level=$level")
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to post low battery bulletin :: ${error.message}")
            }
            onComplete?.invoke()
        }
    }

    fun onCharging(context: Context, onComplete: (() -> Unit)? = null) {
        scope.launch {
            runCatching {
                if (!FileApexAndroidBootstrap.ensureInitialized(context.applicationContext)) return@runCatching
                val noteId = consumeActiveNoteId(context) ?: return@runCatching
                FileApexServices.noteRepository.deleteNoteFromAllDevices(noteId)
                Log.i(TAG, "Retracted low battery bulletin noteId=$noteId (device charging)")
            }.onFailure { error ->
                Log.w(TAG, "Failed to retract low battery bulletin :: ${error.message}")
            }
            onComplete?.invoke()
        }
    }

    fun onBatteryChanged(context: Context, intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (plugged != 0 && readActiveNoteId(context) != null) {
            onCharging(context)
        }
    }

    private fun reconcileOnLaunch(context: Context) {
        scope.launch {
            if (!FileApexAndroidBootstrap.ensureInitialized(context.applicationContext)) return@launch
            val active = readActiveNoteId(context) ?: return@launch
            if (isDevicePluggedIn(context)) {
                runCatching {
                    FileApexServices.noteRepository.deleteNoteFromAllDevices(active)
                    clearActiveNoteId(context)
                    Log.i(TAG, "Launch reconcile retracted stale low battery bulletin noteId=$active")
                }.onFailure { error ->
                    Log.w(TAG, "Launch reconcile retract failed :: ${error.message}")
                }
            }
        }
    }

    private fun registerDynamicReceiver(context: Context) {
        if (receiverRegistered) return
        val receiver = BatteryBulletinReceiver(forDynamicRegistration = true)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            dynamicReceiver = receiver
            receiverRegistered = true
            Log.i(TAG, "Registered dynamic battery bulletin receiver")
        }.onFailure { error ->
            Log.w(TAG, "Dynamic battery bulletin receiver registration failed :: ${error.message}")
        }
    }

    private fun readBatteryLevelPercent(context: Context): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    }

    private fun isDevicePluggedIn(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun readActiveNoteId(context: Context): String? {
        synchronized(lock) {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_NOTE_ID, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun setActiveNoteId(context: Context, noteId: String) {
        synchronized(lock) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_NOTE_ID, noteId)
                .apply()
        }
    }

    private fun consumeActiveNoteId(context: Context): String? {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val noteId = prefs.getString(KEY_ACTIVE_NOTE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
            if (noteId != null) {
                prefs.edit().remove(KEY_ACTIVE_NOTE_ID).apply()
            }
            return noteId
        }
    }

    private fun clearActiveNoteId(context: Context) {
        synchronized(lock) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ACTIVE_NOTE_ID)
                .apply()
        }
    }
}
