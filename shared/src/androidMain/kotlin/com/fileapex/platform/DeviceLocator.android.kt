package com.fileapex.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.domain.device.PhoneLocator
import com.fileapex.i18n.AppI18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

actual fun triggerLocalLocatorSound(continuous: Boolean) {
    if (continuous) {
        androidApplicationContextOrNull()?.let { LocatorAlarm.start(it) }
        return
    }
    CoroutineScope(Dispatchers.Default).launch {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            repeat(4) {
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                delay(400)
            }
            toneGen.release()
        }
    }
}

actual fun stopLocalLocatorSound() {
    androidApplicationContextOrNull()?.let { LocatorAlarm.stop(it) }
}

/** Loud looping alarm that runs until the user stops it from the notification. */
internal object LocatorAlarm {
    private const val NOTIFICATION_ID = 5290
    private const val NOTIFICATION_TAG = "fileapex.locate"
    private var player: MediaPlayer? = null
    private const val TIMEOUT_MS = 5 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeout: Job? = null
    private var savedAlarmVolume = -1

    @Synchronized
    fun start(context: Context) {
        if (player != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            next.setDataSource(context, uri)
            next.isLooping = true
            next.prepare()
            next.start()
        } catch (error: java.io.IOException) {
            failStart(context, next, error)
            return
        } catch (error: IllegalStateException) {
            failStart(context, next, error)
            return
        }
        player = next
        postNotification(context)
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            stop(context)
        }
    }

    private fun failStart(context: Context, failed: MediaPlayer, error: Exception) {
        println("LocatorAlarm: could not start - ${error::class.simpleName}")
        failed.release()
        context.getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        savedAlarmVolume = -1
    }

    /** The user stopped the alarm on this phone: silence it and tell the computer it was found. */
    fun stopByUser(context: Context) {
        stop(context)
        scope.launch { PhoneLocator.reportFound() }
    }

    @Synchronized
    fun stop(context: Context) {
        timeout?.cancel()
        timeout = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        if (savedAlarmVolume >= 0) {
            context.getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            savedAlarmVolume = -1
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
    }

    private fun postNotification(context: Context) {
        AndroidNotificationChannels.ensureLocatePhoneChannel(context)
        val stop = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, LocatorStopReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AndroidNotificationChannels.LOCATE_PHONE)
            .setSmallIcon(AndroidNotificationChannels.noteSmallIcon)
            .setContentTitle(AppI18n.t("locate_phone_ringing"))
            .addAction(0, AppI18n.t("locate_phone_stop"), stop)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification) }
    }
}

class LocatorStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LocatorAlarm.stopByUser(context)
    }
}
