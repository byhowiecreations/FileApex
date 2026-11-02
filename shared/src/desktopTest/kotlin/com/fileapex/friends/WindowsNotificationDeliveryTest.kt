package com.fileapex.friends

import com.fileapex.platform.*
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WindowsNotificationDeliveryTest {
    private class User : WindowsNotifyUser32 {
        lateinit var callback: WindowsNotifyProc
        val messages = LinkedBlockingQueue<Triple<Int, Pointer?, Pointer?>>()
        override fun RegisterClassExW(data: WindowsNotifyWindowClass): Short { callback = data.callback!!; return 1 }
        override fun CreateWindowExW(exStyle: Int, name: WString, title: WString, style: Int, x: Int, y: Int, width: Int, height: Int, parent: Pointer?, menu: Pointer?, instance: Pointer?, parameter: Pointer?) = Pointer(1)
        override fun DefWindowProcW(window: Pointer?, message: Int, wParam: Pointer?, lParam: Pointer?): Pointer? = null
        override fun PostMessageW(window: Pointer?, message: Int, wParam: Pointer?, lParam: Pointer?): Boolean { messages.put(Triple(message, wParam, lParam)); return true }
        override fun GetMessageW(message: WindowsNotifyMessage, window: Pointer?, min: Int, max: Int): Int {
            val next = messages.take()
            if (next.first == -1) return 0
            message.message = next.first; message.wParam = next.second; message.lParam = next.third
            return 1
        }
        override fun TranslateMessage(message: WindowsNotifyMessage) = true
        override fun DispatchMessageW(message: WindowsNotifyMessage): Pointer? = callback.invoke(Pointer(1), message.message, message.wParam, message.lParam)
        override fun DestroyWindow(window: Pointer?) = true
        override fun UnregisterClassW(name: WString, instance: Pointer?) = true
        override fun LoadIconW(instance: Pointer?, name: Pointer) = Pointer(2)
        override fun PostQuitMessage(code: Int) { messages.put(Triple(-1, null, null)) }
        fun click(id: Int) { messages.put(Triple(WindowsNotificationDelivery.CALLBACK_MESSAGE, Pointer(id.toLong()), Pointer(0x405))) }
    }

    @Test fun oldBalloonEventsCannotOpenTheReplacementNotification() = runBlocking {
        val user = User()
        val posted = LinkedBlockingQueue<Int>()
        val removed = LinkedBlockingQueue<Int>()
        val shell = object : WindowsNotifyShell32 {
            override fun Shell_NotifyIconW(message: Int, data: WindowsNotifyData): Boolean {
                if (message == 0) posted.put(data.id)
                if (message == 2) removed.put(data.id)
                return true
            }
        }
        val kernel = object : WindowsNotifyKernel32 { override fun GetModuleHandleW(name: WString?) = Pointer(3) }
        val delivery = WindowsNotificationDelivery { WindowsNotificationApi(user, shell, kernel) }
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        try {
            delivery.show("Alex", "first.txt") { first.complete(it) }
            val firstId = withContext(Dispatchers.IO) { posted.poll(5, java.util.concurrent.TimeUnit.SECONDS) }!!
            delivery.close()
            assertFalse(withTimeout(5_000L) { first.await() })
            delivery.show("Blair", "second.txt") { second.complete(it) }
            val secondId = withContext(Dispatchers.IO) { posted.poll(5, java.util.concurrent.TimeUnit.SECONDS) }!!
            assertNotEquals(firstId, secondId)
            user.click(firstId)
            user.click(secondId)
            assertTrue(withTimeout(5_000L) { second.await() })
            assertEquals(listOf(firstId, secondId), listOf(removed.poll(5, java.util.concurrent.TimeUnit.SECONDS), removed.poll(5, java.util.concurrent.TimeUnit.SECONDS)))
        } finally { delivery.shutdown() }
    }
}
