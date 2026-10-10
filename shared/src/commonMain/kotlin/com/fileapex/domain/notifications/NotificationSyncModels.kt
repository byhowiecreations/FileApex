package com.fileapex.domain.notifications

import kotlinx.serialization.Serializable

@Serializable
enum class NotificationKind { MESSAGE, EMAIL, OTHER }

/** Encrypted inside [NotificationEventRequest.ciphertext]; never written to disk or logs. */
@Serializable
data class NotificationPayload(
    val remove: Boolean = false,
    val key: String,
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val text: String = "",
    val kind: NotificationKind = NotificationKind.OTHER,
    val postedAtEpochMs: Long = 0L,
    /** Keys still showing on the phone. The receiver drops anything else, so a missed removal heals. */
    val activeKeys: List<String>? = null,
    /** Who the notification is from: the sender, or the group chat name. Empty when the app gave only a title. */
    val conversation: String = "",
    /** Recent message lines, oldest first, for threads that carry several messages. */
    val lines: List<String> = emptyList(),
    /** True when the phone can fill in this notification's reply box. */
    val canReply: Boolean = false
)

private const val MAX_FIELD_CHARS = 1_000
private const val MAX_LINES = 10
private const val MAX_ACTIVE_KEYS = 200

/** Everything from the other device is hostile input: cap each field before it is stored or shown. */
fun NotificationPayload.sanitized(): NotificationPayload = copy(
    key = key.take(MAX_FIELD_CHARS),
    packageName = packageName.take(MAX_FIELD_CHARS),
    appLabel = appLabel.take(MAX_FIELD_CHARS),
    title = title.take(MAX_FIELD_CHARS),
    text = text.take(MAX_FIELD_CHARS),
    conversation = conversation.take(MAX_FIELD_CHARS),
    lines = lines.takeLast(MAX_LINES).map { it.take(MAX_FIELD_CHARS) },
    activeKeys = activeKeys?.take(MAX_ACTIVE_KEYS)?.map { it.take(MAX_FIELD_CHARS) }
)

@Serializable
enum class NotificationAction { DISMISS, REPLY }

/** Mac to phone, encrypted inside [NotificationCommandRequest.ciphertext]. */
@Serializable
data class NotificationCommand(
    val action: NotificationAction,
    val keys: List<String> = emptyList(),
    val text: String = ""
)

@Serializable
data class NotificationCommandRequest(
    val senderDeviceId: String,
    val senderPublicKey: String = "",
    val ciphertext: String = "",
    val sentAtEpochMs: Long = 0L
)

@Serializable
data class NotificationEventRequest(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val senderPublicKey: String = "",
    val ciphertext: String = "",
    val sentAtEpochMs: Long = 0L
)

/** Mac asks the phone for what it is showing now; the phone's own tray is the queue, so nothing is stored. */
@Serializable
data class NotificationSnapshotRequest(
    val senderDeviceId: String,
    val senderPublicKey: String = ""
)

@Serializable
data class NotificationSnapshotResponse(
    val ciphertext: String = ""
)

/** Encrypted inside [NotificationSnapshotResponse.ciphertext]. */
@Serializable
data class NotificationSnapshot(
    val items: List<NotificationPayload> = emptyList()
)

@Serializable
data class NotificationSyncStatus(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val accessGranted: Boolean = false
)

object PhoneSettingsPage {
    const val BROADCAST_NOTIFICATIONS = "broadcast_notifications"
    const val CLIPBOARD = "clipboard"
}

@Serializable
data class PhoneSettingsPromptRequest(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val page: String
)

enum class NotificationAppGroup {
    MESSAGING, EMAIL, SHOPPING, AI, HEALTH, WEATHER, UTILITIES, OTHER
}

data class NotificationAppInfo(
    val packageName: String,
    val label: String,
    val kind: NotificationKind,
    val group: NotificationAppGroup
)
