package com.fileapex.network

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.presence.PresenceBackgroundWake
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.platform.ServiceWatchdogScheduler
import com.fileapex.platform.ServiceWatchdogState
import com.fileapex.platform.ShareServerForegroundNotification
import com.fileapex.platform.ShareServerKeepAliveCoordinator
import com.fileapex.platform.ShareServerPendingStart
import com.fileapex.platform.ShareServerRestartCoordinator

/**
 * Foreground service that keeps the LAN share server alive via [ServerLifecycleManager].
 *
 * The persistent server notification is posted once via [ShareServerForegroundNotification] and
 * is never re-issued during AlarmManager re-asserts or other background housekeeping.
 *
 * Background recovery uses the **20-minute** AlarmManager watchdog ([ServiceWatchdogScheduler] /
 * [com.fileapex.util.TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS]) — not an in-process poll loop.
 * Cloud peer visibility uses the separate **10-minute** Firestore heartbeat
 * ([com.fileapex.cloud.CloudPresenceHeartbeat]).
 *
 * UDP peer-wake listening lives only in this FGS — there is no separate process-level wake
 * service; peers cannot wake the device via UDP until this service (or a watchdog/UI restart)
 * is running again.
 */
class FileShareServerService : Service() {
    private var wakeReceiver: UdpWakeReceiver? = null
    private var isForegroundPromoted = false

    override fun onCreate() {
        super.onCreate()
        ShareServerForegroundNotification.resetPostedState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromForeground = isForegroundStart(intent)
        val reassert = intent?.action == ShareServerKeepAliveCoordinator.ACTION_REASSERT
        val stickyRestart = intent == null
        if (reassert && isForegroundPromoted) {
            runBackgroundHousekeeping()
            return START_STICKY
        }
        if (!isForegroundPromoted) {
            val promoted = if (fromForeground) {
                promoteToForegroundFromUi()
            } else {
                promoteToForegroundSafely()
            }
            if (!promoted) {
                handlePromotionFailure(fromForeground = fromForeground, stickyRestart = stickyRestart)
                return START_NOT_STICKY
            }
            isForegroundPromoted = true
            ShareServerPendingStart.clear(this)
            ShareServerKeepAliveCoordinator.onForegroundServiceActive(this)
        } else if (fromForeground && !ShareServerForegroundNotification.isPosted()) {
            // POST_NOTIFICATIONS may have been denied on first promote — one UI retry only.
            runCatching { postStaticNotificationOnce() }
                .onFailure { error ->
                    Log.w(TAG, "Foreground notification retry failed :: ${error.message}")
                }
            ShareServerPendingStart.clear(this)
        }
        runBackgroundHousekeeping()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ServiceWatchdogScheduler.isWatchdogEnabled(this)) {
            Log.i(TAG, "Task removed — scheduling immediate watchdog recovery")
            ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(
            TAG,
            "FGS runtime quota exceeded (type=$fgsType, startId=$startId) — " +
                "stopping cleanly and scheduling deferred watchdog restart"
        )
        ServiceWatchdogState.markTimeoutStop(this)
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
        stopSelf(startId)
    }

    override fun onDestroy() {
        ShareServerKeepAliveCoordinator.onForegroundServiceInactive(this)
        ShareServerForegroundNotification.resetPostedState()
        val cleanStop = ServiceWatchdogState.consumeCleanStop(this)
        val timeoutStop = ServiceWatchdogState.consumeTimeoutStop(this)
        when {
            cleanStop || !ServiceWatchdogScheduler.isWatchdogEnabled(this) -> {
                ServiceWatchdog.cancelAlarm()
                if (cleanStop) {
                    ServiceWatchdogScheduler.clearShareServerHeartbeat(this)
                }
            }
            timeoutStop -> {
                Log.i(TAG, "FGS stopped after runtime timeout — deferred watchdog restart pending")
            }
            else -> {
                Log.i(TAG, "Unexpected FGS stop — scheduling immediate watchdog recovery")
                ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
            }
        }
        wakeReceiver?.stop()
        wakeReceiver = null
        ServerLifecycleManager.stop(androidLog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isForegroundStart(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) == true ||
            intent?.action == ACTION_START

    /**
     * Server lifecycle + watchdog bookkeeping — never touches the notification manager.
     */
    private fun runBackgroundHousekeeping() {
        ensureServerRunning()
        recordServiceHeartbeat()
        if (wakeReceiver == null) {
            startWakeListener()
        }
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
    }

    private fun handlePromotionFailure(fromForeground: Boolean, stickyRestart: Boolean) {
        if (fromForeground) {
            Log.w(TAG, "Foreground promotion failed from UI — server not started")
            stopSelf()
            return
        }
        val trigger = if (stickyRestart) {
            ShareServerRestartCoordinator.RestartTrigger.STICKY_RESTART
        } else {
            ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
        }
        ShareServerRestartCoordinator.onForegroundPromotionBlocked(this, trigger)
        stopSelf()
    }

    /** UI path — must not crash the app when FGS/notification policy blocks promotion. */
    private fun promoteToForegroundFromUi(): Boolean {
        return try {
            postStaticNotificationOnce()
            true
        } catch (error: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "UI FGS not allowed :: ${error.message}")
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "UI FGS security denied :: ${error.message}")
            false
        } catch (error: IllegalStateException) {
            Log.w(TAG, "UI FGS illegal state :: ${error.message}")
            false
        }
    }

    /** Guarded promotion for watchdog / boot / sticky restart — must not crash the process. */
    private fun promoteToForegroundSafely(): Boolean {
        return try {
            postStaticNotificationOnce()
            true
        } catch (error: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Background FGS not allowed :: ${error.message}")
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Background FGS security denied :: ${error.message}")
            false
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Background FGS illegal state :: ${error.message}")
            false
        }
    }

    private fun postStaticNotificationOnce() {
        ShareServerForegroundNotification.postOnce(this)
        ShareServerPendingStart.clear(this)
    }

    private fun startWakeListener() {
        if (wakeReceiver != null) return
        val receiver = UdpWakeReceiver(
            onWakeAccepted = {
                ServerLifecycleManager.ensureRunning(androidLog)
                PresenceBackgroundWake.onRemoteWakeSignal(sourceDeviceId = null)
            },
            onLog = { message -> Log.i(TAG, message) }
        )
        wakeReceiver = receiver
        receiver.start()
    }

    private fun ensureServerRunning() {
        ServerLifecycleManager.ensureRunning(androidLog)
    }

    /** Records FGS liveness for the 20-minute AlarmManager recovery watchdog (not cloud presence). */
    private fun recordServiceHeartbeat() {
        ServiceWatchdogScheduler.recordShareServerHeartbeat(this)
    }

    companion object {
        private const val TAG = "FileApexServerService"
        const val ACTION_START = "com.fileapex.action.START_SHARE_SERVER"
        const val EXTRA_FROM_FOREGROUND = "extra_from_foreground"
        const val SERVER_PORT = LocalIdentity.DEFAULT_SHARE_PORT

        private val androidLog: (String, Throwable?) -> Unit = { message, error ->
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.i(TAG, message)
            }
        }
    }
}
