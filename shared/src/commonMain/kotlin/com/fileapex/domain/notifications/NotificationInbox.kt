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
 */
object NotificationInbox {
    private const val MAX_ITEMS = 200

    private val _items = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val items: StateFlow<List<NotificationPayload>> = _items.asStateFlow()

    fun counts(items: List<NotificationPayload>): NotificationCounts = NotificationCounts(
        messages = items.count { it.kind == NotificationKind.MESSAGE },
        emails = items.count { it.kind == NotificationKind.EMAIL },
        other = items.count { it.kind == NotificationKind.OTHER }
    )

    fun apply(payload: NotificationPayload) {
        if (payload.key.isBlank()) return
        _items.update { current ->
            val without = current.filterNot { it.key == payload.key }
            val next = if (payload.remove) without else (listOf(payload) + without).take(MAX_ITEMS)
            val active = payload.activeKeys?.toSet()
            if (active == null) next else next.filter { it.key in active }
        }
    }

    /** The phone's complete current list, fetched after this computer was asleep or offline. */
    fun replaceAll(payloads: List<NotificationPayload>) {
        _items.value = payloads.filter { it.key.isNotBlank() && !it.remove }
            .distinctBy { it.key }
            .take(MAX_ITEMS)
    }

    fun removeKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val gone = keys.toSet()
        _items.update { current -> current.filterNot { it.key in gone } }
    }

    fun clear() {
        _items.value = emptyList()
    }
}
