package com.fileapex.platform

import com.fileapex.friends.DesktopFriendNotificationSink
import com.fileapex.util.sha256Hex
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native

internal fun interface FriendsNotificationCallback : Callback { fun invoke(id: String) }

internal interface FriendsNotificationNative : Library {
    fun fileapex_friends_notifications_install(callback: FriendsNotificationCallback): Int
    fun fileapex_friends_notifications_permission()
    fun fileapex_friends_notifications_post(id: String, title: String, body: String)
    fun fileapex_friends_notifications_remove(id: String)
}

internal class MacFriendNotificationSink(private val native: FriendsNotificationNative) : DesktopFriendNotificationSink {
    private val actions = LinkedHashMap<String, () -> Unit>()
    // JNA callbacks must remain strongly reachable until the native delegate is gone.
    private val callback = FriendsNotificationCallback { id -> synchronized(actions) { actions.remove(id) }?.invoke() }

    init { check(native.fileapex_friends_notifications_install(callback) == 1) { "Native notifications need an app bundle" } }

    override fun post(id: String, title: String, body: String, onOpen: () -> Unit) {
        val key = nativeId(id)
        synchronized(actions) {
            actions[key] = onOpen
            while (actions.size > 64) {
                val oldest = actions.keys.first()
                actions.remove(oldest)
                native.fileapex_friends_notifications_remove(oldest)
            }
        }
        native.fileapex_friends_notifications_post(key, title, body)
    }

    override fun remove(id: String) {
        val key = nativeId(id)
        synchronized(actions) { actions.remove(key) }
        native.fileapex_friends_notifications_remove(key)
    }

    fun requestPermission() = native.fileapex_friends_notifications_permission()

    fun dispose() {
        synchronized(actions) {
            actions.keys.forEach { native.fileapex_friends_notifications_remove(it) }
            actions.clear()
        }
    }

    private fun nativeId(id: String) = "friends-" + sha256Hex(id.encodeToByteArray())

    companion object {
        fun load(): MacFriendNotificationSink? {
            if (!DesktopPlatformPaths.isMacOs()) return null
            val file = DesktopMacTrayBridge.resolveDylib() ?: return null
            return try { MacFriendNotificationSink(Native.load(file.absolutePath, FriendsNotificationNative::class.java)) }
            catch (e: LinkageError) { println("FriendsNotifier: native notifications unavailable :: ${e::class.simpleName}"); null }
            catch (e: IllegalStateException) { println("FriendsNotifier: native notifications require a packaged app"); null }
        }
    }
}
