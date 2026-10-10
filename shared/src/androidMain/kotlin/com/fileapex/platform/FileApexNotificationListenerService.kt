package com.fileapex.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.fileapex.di.FileApexServices
import com.fileapex.domain.notifications.NotificationBroadcastPolicy
import com.fileapex.domain.notifications.NotificationBroadcaster
import com.fileapex.domain.notifications.NotificationKind
import com.fileapex.domain.notifications.NotificationPayload
import com.fileapex.domain.notifications.NotificationSyncFeature
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Reads notifications only while the user has turned Broadcast Notifications on and granted access.
 * Nothing about a notification is logged or stored on this device.
 */
class FileApexNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One consumer keeps a notification's post and removal in the order Android reported them.
    private val events = Channel<Pair<StatusBarNotification, Boolean>>(Channel.UNLIMITED)

    companion object {
        @Volatile
        var instance: FileApexNotificationListenerService? = null
            private set

        private const val MAX_LINES = 6
        private const val MAX_SNAPSHOT_ITEMS = 100
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for ((sbn, removed) in events) {
                try {
                    process(sbn, removed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Coroutine entry point: one bad event must not stop later ones.
                    println("NotificationListener: event failed - ${error::class.simpleName}")
                }
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    fun dismiss(keys: List<String>, allowedPackages: Set<String>) {
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            return
        } ?: return
        val allowed = active.filter { it.key in keys && it.packageName in allowedPackages }.map { it.key }
        if (allowed.isNotEmpty()) cancelNotifications(allowed.toTypedArray())
    }

    fun reply(key: String, text: String, allowedPackages: Set<String>): Boolean {
        val sbn = try {
            activeNotifications?.firstOrNull { it.key == key && it.packageName in allowedPackages }
        } catch (_: SecurityException) {
            null
        } ?: return false
        val action = sbn.notification.actions?.firstOrNull { replyInputOf(it) != null } ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(replyInputOf(action)!!.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(applicationContext, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn, removed = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handle(sbn, removed = true)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        events.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(sbn: StatusBarNotification, removed: Boolean) {
        if (!NotificationSyncFeature.ENABLED) return
        // Callbacks arrive on the main thread; process start-up and the network call must not run there.
        events.trySend(sbn to removed)
    }

    private fun replyInputOf(action: Notification.Action): RemoteInput? =
        action.remoteInputs?.firstOrNull { it.allowFreeFormInput }

    /** Sender or chat name plus the recent message lines, read from the app's messaging style when it has one. */
    private fun describe(notification: Notification, title: String, text: String): Triple<String, List<String>, Boolean> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val canReply = notification.actions?.any { replyInputOf(it) != null } == true
        if (style != null && style.messages.isNotEmpty()) {
            val group = style.isGroupConversation
            val lines = style.messages.takeLast(MAX_LINES).mapNotNull { message ->
                val body = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = message.person?.name?.toString()
                if (group && !sender.isNullOrBlank()) "$sender: $body" else body
            }
            val conversation = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: style.messages.last().person?.name?.toString()?.takeIf { it.isNotBlank() }
                ?: title
            return Triple(conversation, lines, canReply)
        }
        val inboxLines = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString() }?.filter { it.isNotBlank() }?.takeLast(MAX_LINES)
        return Triple(title, inboxLines?.takeIf { it.isNotEmpty() } ?: listOf(text).filter { it.isNotBlank() }, canReply)
    }

    private fun activeAllowedKeys(allowedPackages: Set<String>): List<String>? = try {
        activeNotifications?.filter { it.packageName in allowedPackages }?.map { it.key }
    } catch (_: SecurityException) {
        null
    }

    private suspend fun process(sbn: StatusBarNotification, removed: Boolean) {
        if (!FileApexAndroidBootstrap.ensureInitialized(applicationContext)) return
        val settings = FileApexServices.settings
        if (!settings.notificationBroadcastEnabled.value) return
        val allowedPackages = settings.notificationBroadcastApps.value
        val payload = buildPayload(sbn, removed, allowedPackages, settings.notificationBroadcastVerificationCodes.value)
        if (payload == null) {
            println("NotificationListener: skipped ${sbn.packageName}, not allowed or filtered as silent, ongoing or a summary")
            return
        }
        val sent = NotificationBroadcaster.deliver(payload)
        println("NotificationListener: ${if (removed) "removal" else "post"} for ${sbn.packageName} sent=$sent")
    }

    /** What the paired computer should show right now, for a computer that was asleep or offline when these posted. */
    fun snapshot(allowedPackages: Set<String>): List<NotificationPayload> {
        val settings = FileApexServices.settings
        if (!settings.notificationBroadcastEnabled.value) return emptyList()
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            return emptyList()
        } ?: return emptyList()
        val codes = settings.notificationBroadcastVerificationCodes.value
        return active
            .filter { it.packageName in allowedPackages }
            .sortedByDescending { it.postTime }
            .mapNotNull { buildPayload(it, removed = false, allowedPackages, codes) }
            .take(MAX_SNAPSHOT_ITEMS)
    }

    private fun buildPayload(
        sbn: StatusBarNotification,
        removed: Boolean,
        allowedPackages: Set<String>,
        allowVerificationCodes: Boolean
    ): NotificationPayload? {
        if (sbn.packageName !in allowedPackages) return null

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (!removed) {
            val ranking = Ranking()
            val importance = if (currentRanking.getRanking(sbn.key, ranking)) {
                ranking.importance
            } else {
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            }
            val candidate = NotificationBroadcastPolicy.Candidate(
                packageName = sbn.packageName,
                title = title,
                text = text,
                ongoing = notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
                silent = importance < android.app.NotificationManager.IMPORTANCE_DEFAULT,
                groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            )
            val config = NotificationBroadcastPolicy.Config(
                enabled = true,
                allowedPackages = allowedPackages,
                allowVerificationCodes = allowVerificationCodes,
                ownPackage = packageName
            )
            if (!NotificationBroadcastPolicy.shouldBroadcast(candidate, config)) return null
        }

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val kind = NotificationBroadcastPolicy.classify(notification.category, sbn.packageName)
        val (conversation, lines, canReply) = if (removed) {
            Triple("", emptyList(), false)
        } else {
            describe(notification, title, text)
        }
        return NotificationPayload(
            remove = removed,
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label,
            title = if (removed) "" else title,
            text = if (removed) "" else text,
            kind = kind,
            conversation = conversation,
            lines = lines,
            canReply = canReply && kind == NotificationKind.MESSAGE,
            postedAtEpochMs = sbn.postTime.takeIf { it > 0L } ?: TimeUtils.now(),
            activeKeys = activeAllowedKeys(allowedPackages)
        )
    }
}
