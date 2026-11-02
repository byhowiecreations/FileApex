package com.fileapex.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal class WindowsNotificationApi(val user: WindowsNotifyUser32, val shell: WindowsNotifyShell32, val kernel: WindowsNotifyKernel32)

internal class WindowsNotificationDelivery(
    private val load: () -> WindowsNotificationApi = {
        WindowsNotificationApi(Native.load("user32", WindowsNotifyUser32::class.java),
            Native.load("shell32", WindowsNotifyShell32::class.java), Native.load("kernel32", WindowsNotifyKernel32::class.java))
    }
) : DesktopNotificationDelivery {
    private class Command(val run: () -> Unit, val failed: () -> Unit)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val ended = AtomicBoolean(false)
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var window: Pointer? = null
    private lateinit var api: WindowsNotificationApi
    private var active: WindowsNotifyData? = null
    private var onDone: ((Boolean) -> Unit)? = null
    private var expiry: Job? = null
    private var nextId = 1
    private val callback = WindowsNotifyProc { hwnd, message, wParam, lParam ->
        when (message) {
            DISPATCH_MESSAGE -> { drain(); null }
            CALLBACK_MESSAGE -> {
                val id = Pointer.nativeValue(wParam).toInt()
                val event = Pointer.nativeValue(lParam).toInt()
                if (id == active?.id && event in setOf(0x403, 0x404, 0x405, 0x202)) finish(event == 0x405 || event == 0x202)
                null
            }
            else -> api.user.DefWindowProcW(hwnd, message, wParam, lParam)
        }
    }

    init { Thread(::messageLoop, "FileApex-FriendsNotifications").apply { isDaemon = true; start() } }

    override fun show(title: String, body: String, done: (Boolean) -> Unit) {
        enqueue({
            finish(false)
            val data = WindowsNotifyData().apply {
                bytes = size(); window = this@WindowsNotificationDelivery.window
                id = nextId++; flags = 1 or 2 or 4 or 16; callbackMessage = CALLBACK_MESSAGE
                icon = api.user.LoadIconW(null, Pointer(32512))
                "FileApex".forEachIndexed { i, char -> tip[i] = char.code.toShort() }
                infoFlags = 1 or 0x80
                setText(title, body)
            }
            active = data; onDone = done
            // Keep full 32-bit IDs in callbacks so an old balloon can never target its replacement.
            if (!api.shell.Shell_NotifyIconW(0, data)) {
                println("FriendsNotifier: Windows notification unavailable (${Native.getLastError()})")
                finish(false)
            } else expiry = timers.launch {
                delay(120_000L)
                enqueue({ if (active?.id == data.id) finish(false) })
            }
        }, { done(false) })
    }

    override fun close() { enqueue({ finish(false) }) }

    override fun shutdown() { enqueue({ finish(false); api.user.PostQuitMessage(0) }) }

    private fun enqueue(run: () -> Unit, failed: () -> Unit = {}) {
        val command = Command(run, failed)
        if (ended.get()) return failed()
        commands.add(command)
        if (ended.get() && commands.remove(command)) return failed()
        window?.let { api.user.PostMessageW(it, DISPATCH_MESSAGE, null, null) }
    }

    private fun drain() {
        while (true) {
            val command = commands.poll() ?: return
            try { command.run() } catch (e: Exception) { command.failed(); throw e }
        }
    }

    private fun finish(clicked: Boolean) {
        val data = active ?: return
        active = null
        expiry?.cancel(); expiry = null
        api.shell.Shell_NotifyIconW(2, data)
        val done = onDone; onDone = null
        done?.invoke(clicked)
    }

    private fun messageLoop() {
        var registered = false
        var instance: Pointer? = null
        val name = WString("FileApex.Friends.Notifications.${UUID.randomUUID()}")
        try {
            api = load()
            instance = api.kernel.GetModuleHandleW(null)
            val type = WindowsNotifyWindowClass().apply {
                size = size(); callback = this@WindowsNotificationDelivery.callback
                this.instance = instance; className = name
            }
            if (api.user.RegisterClassExW(type).toInt() == 0) throw IOException("Could not register notification window")
            registered = true
            window = api.user.CreateWindowExW(0, name, WString("FileApex"), 0, 0, 0, 0, 0, null, null, instance, null)
                ?: throw IOException("Could not create notification window")
            drain()
            val message = WindowsNotifyMessage()
            while (true) {
                val result = api.user.GetMessageW(message, null, 0, 0)
                if (result == 0) break
                if (result == -1) throw IOException("Notification message loop failed")
                api.user.TranslateMessage(message)
                api.user.DispatchMessageW(message)
            }
        } catch (e: Exception) {
            println("FriendsNotifier: Windows notification host stopped :: ${e::class.simpleName}")
        } catch (e: LinkageError) {
            println("FriendsNotifier: Windows notification binding unavailable :: ${e::class.simpleName}")
        } finally {
            ended.set(true)
            timers.cancel()
            if (::api.isInitialized) {
                finish(false)
                window?.let { api.user.DestroyWindow(it) }
                if (registered) api.user.UnregisterClassW(name, instance)
            }
            window = null
            while (true) (commands.poll() ?: break).failed()
        }
    }

    companion object {
        internal const val CALLBACK_MESSAGE = 0x8001
        private const val DISPATCH_MESSAGE = 0x8002
    }
}
