package com.fileapex.platform

import com.fileapex.friends.DesktopFriendNotificationSink

internal interface DesktopNotificationDelivery {
    fun show(title: String, body: String, done: (Boolean) -> Unit)
    fun close()
    fun shutdown() = close()
}

internal class SerialDesktopNotificationSink(private val delivery: DesktopNotificationDelivery) : DesktopFriendNotificationSink {
    private class Notice(val title: String, val body: String, val open: () -> Unit)
    private val pending = LinkedHashMap<String, Notice>()
    private var active: Pair<String, Notice>? = null
    private var disposed = false

    @Synchronized override fun post(id: String, title: String, body: String, onOpen: () -> Unit) {
        if (disposed) return
        if (active?.first == id) remove(id)
        pending[id] = Notice(title, body, onOpen)
        while (pending.size + (if (active != null) 1 else 0) > 64) pending.remove(pending.keys.first())
        advance()
    }

    @Synchronized fun dispose() {
        disposed = true
        pending.clear()
        active = null
        delivery.shutdown()
    }

    @Synchronized override fun remove(id: String) {
        pending.remove(id)
        if (active?.first == id) {
            active = null
            delivery.close()
            advance()
        }
    }

    private fun advance() {
        if (active != null || pending.isEmpty()) return
        val id = pending.keys.first()
        val notice = pending.remove(id) ?: return
        active = id to notice
        delivery.show(notice.title, notice.body) { clicked ->
            val open = synchronized(this) {
                if (active?.second !== notice) return@show
                active = null
                advance()
                notice.open.takeIf { clicked }
            }
            open?.invoke()
        }
    }
}
