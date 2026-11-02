package com.fileapex.platform

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

internal fun interface WindowsNotifyProc : StdCallLibrary.StdCallCallback {
    fun invoke(window: Pointer?, message: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
}

@Structure.FieldOrder("size", "style", "callback", "classExtra", "windowExtra", "instance", "icon", "cursor", "background", "menuName", "className", "smallIcon")
internal class WindowsNotifyWindowClass : Structure() {
    @JvmField var size = 0
    @JvmField var style = 0
    @JvmField var callback: WindowsNotifyProc? = null
    @JvmField var classExtra = 0
    @JvmField var windowExtra = 0
    @JvmField var instance: Pointer? = null
    @JvmField var icon: Pointer? = null
    @JvmField var cursor: Pointer? = null
    @JvmField var background: Pointer? = null
    @JvmField var menuName: WString? = null
    @JvmField var className: WString? = null
    @JvmField var smallIcon: Pointer? = null
}

@Structure.FieldOrder("window", "message", "wParam", "lParam", "time", "x", "y", "privateValue")
internal class WindowsNotifyMessage : Structure() {
    @JvmField var window: Pointer? = null
    @JvmField var message = 0
    @JvmField var wParam: Pointer? = null
    @JvmField var lParam: Pointer? = null
    @JvmField var time = 0
    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var privateValue = 0
}

@Structure.FieldOrder("bytes", "window", "id", "flags", "callbackMessage", "icon", "tip", "state", "stateMask", "info", "timeoutOrVersion", "title", "infoFlags", "guid", "balloonIcon")
internal class WindowsNotifyData : Structure() {
    @JvmField var bytes = 0
    @JvmField var window: Pointer? = null
    @JvmField var id = 0
    @JvmField var flags = 0
    @JvmField var callbackMessage = 0
    @JvmField var icon: Pointer? = null
    @JvmField var tip = ShortArray(128)
    @JvmField var state = 0
    @JvmField var stateMask = 0
    @JvmField var info = ShortArray(256)
    @JvmField var timeoutOrVersion = 0
    @JvmField var title = ShortArray(64)
    @JvmField var infoFlags = 0
    @JvmField var guid = ByteArray(16)
    @JvmField var balloonIcon: Pointer? = null

    fun setText(heading: String, body: String) {
        fun fill(buffer: ShortArray, text: String) {
            buffer.fill(0)
            var count = minOf(text.length, buffer.size - 1)
            if (count > 0 && text[count - 1].isHighSurrogate()) count--
            repeat(count) { buffer[it] = text[it].code.toShort() }
        }
        fill(title, heading)
        fill(info, body)
    }
}

internal interface WindowsNotifyUser32 : StdCallLibrary {
    fun RegisterClassExW(data: WindowsNotifyWindowClass): Short
    fun CreateWindowExW(exStyle: Int, name: WString, title: WString, style: Int, x: Int, y: Int, width: Int, height: Int,
                        parent: Pointer?, menu: Pointer?, instance: Pointer?, parameter: Pointer?): Pointer?
    fun DefWindowProcW(window: Pointer?, message: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
    fun PostMessageW(window: Pointer?, message: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    fun GetMessageW(message: WindowsNotifyMessage, window: Pointer?, min: Int, max: Int): Int
    fun TranslateMessage(message: WindowsNotifyMessage): Boolean
    fun DispatchMessageW(message: WindowsNotifyMessage): Pointer?
    fun DestroyWindow(window: Pointer?): Boolean
    fun UnregisterClassW(name: WString, instance: Pointer?): Boolean
    fun LoadIconW(instance: Pointer?, name: Pointer): Pointer?
    fun PostQuitMessage(code: Int)
}

internal interface WindowsNotifyKernel32 : StdCallLibrary { fun GetModuleHandleW(name: WString?): Pointer? }
internal interface WindowsNotifyShell32 : StdCallLibrary { fun Shell_NotifyIconW(message: Int, data: WindowsNotifyData): Boolean }
