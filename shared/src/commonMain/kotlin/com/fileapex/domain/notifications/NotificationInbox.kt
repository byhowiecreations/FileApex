package com.fileapex.domain.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NotificationCounts(
    val messages: Int = 0,
    val emails: Int = 0,
    val other: Int = 0
)

/**
 * Notifications received from a paired phone. Held in memory only: nothing is written to disk
 * or logged, and the list is gone when the app exits.
 * TEMPORARY EXCEPTION: [NotificationDebugLog] writes metadata-only lines while it is enabled; turn it off after testing.
 */
object NotificationInbox {
    private const val MAX_ITEMS = 200

    private val _items = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val items: StateFlow<List<NotificationPayload>> = _items.asStateFlow()

    /** Items from one phone, or all of them when [sourceDeviceId] is null. */
    fun from(items: List<NotificationPayload>, sourceDeviceId: String?): List<NotificationPayload> =
        if (sourceDeviceId == null) items else items.filter { it.sourceDeviceId == sourceDeviceId }

    fun counts(items: List<NotificationPayload>, sourceDeviceId: String? = null): NotificationCounts {
        val own = from(items, sourceDeviceId)
        return NotificationCounts(
            messages = own.count { it.kind == NotificationKind.MESSAGE },
            emails = own.count { it.kind == NotificationKind.EMAIL },
            other = own.count { it.kind == NotificationKind.OTHER }
        )
    }

    fun apply(payload: NotificationPayload) {
        if (payload.key.isBlank()) return
        _items.update { current ->
            val without = current.filterNot { it.key == payload.key && it.sourceDeviceId == payload.sourceDeviceId }
            val next = if (payload.remove) without else (listOf(payload) + without).take(MAX_ITEMS)
            val active = payload.activeKeys?.toSet()
            // The active list describes one phone; items from other phones are not its to drop.
            if (active == null) next else next.filter { it.sourceDeviceId != payload.sourceDeviceId || it.key in active }
        }
        NotificationDebugLog.log(
            "event from=${NotificationDebugLog.short(payload.sourceDeviceId)} ${if (payload.remove) "remove" else "post"} key=${NotificationDebugLog.short(payload.key)} " +
                "pkg=${payload.packageName} kind=${payload.kind.name} active=${payload.activeKeys?.size ?: -1} -> " +
                NotificationDebugLog.summary(_items.value)
        )
    }

    /** The phone's complete current list, fetched after this computer was asleep or offline. */
    fun replaceAll(payloads: List<NotificationPayload>, sourceDeviceId: String = "") {
        val fresh = payloads.filter { it.key.isNotBlank() && !it.remove }
            .map { it.copy(sourceDeviceId = sourceDeviceId) }
            .distinctBy { it.key }
        _items.update { current -> (fresh + current.filterNot { it.sourceDeviceId == sourceDeviceId }).take(MAX_ITEMS) }
        NotificationDebugLog.log("snapshot from=${NotificationDebugLog.short(sourceDeviceId)} received=${payloads.size} -> " + NotificationDebugLog.summary(_items.value))
    }

    fun removeKeys(keys: Collection<String>, sourceDeviceId: String = "") {
        if (keys.isEmpty()) return
        val gone = keys.toSet()
        _items.update { current -> current.filterNot { it.key in gone && it.sourceDeviceId == sourceDeviceId } }
        NotificationDebugLog.log("removeKeys ${keys.size} -> " + NotificationDebugLog.summary(_items.value))
    }

    fun clear() {
        _items.value = emptyList()
        NotificationDebugLog.log("clear")
    }
}
