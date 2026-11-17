package com.fileapex.platform

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.GraphicsEnvironment
import java.awt.Window

/** AWT top-left Y to the Cocoa bottom-left Y, which is measured from the bottom of the primary screen. */
object MacWindowCoordinates {
    fun cocoaY(awtY: Float, height: Float, primaryScreenHeight: Float): Float = primaryScreenHeight - awtY - height
}

@Structure.FieldOrder("x", "y", "width", "height")
internal class NsRect(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0
) : Structure(), Structure.ByValue

private class MainQueueWork(private val work: () -> Unit) : Callback {
    @Suppress("unused")
    fun invoke(context: Pointer?) = work()
}

/** Calls `-[NSWindow setFrame:display:animate:]` on the AppKit main thread through JNA. */
internal object MacNsWindowFrame {
    private val objc: NativeLibrary by lazy { NativeLibrary.getInstance("objc") }
    private val system: NativeLibrary by lazy { NativeLibrary.getInstance("System") }
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<MainQueueWork>()

    /** True when the move was handed to AppKit (animated, or at once when [animated] is false); false when the window or the selector could not be reached. */
    fun animate(window: Window, target: WindowFrame, animated: Boolean = true): Boolean {
        val pointer = DesktopMacNativeWindow.nsWindowPointer(window) ?: return false
        return try {
            val msgSend = objc.getFunction("objc_msgSend")
            val register = objc.getFunction("sel_registerName")
            val setFrame = register.invokePointer(arrayOf("setFrame:display:animate:"))
            val responds = register.invokePointer(arrayOf("respondsToSelector:"))
            val window = Pointer(pointer)
            if (msgSend.invokeInt(arrayOf(window, responds, setFrame)).toByte() == 0.toByte()) return false
            val primaryHeight = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds.height.toFloat()
            val rect = NsRect(
                target.x.toDouble(),
                MacWindowCoordinates.cocoaY(target.y, target.height, primaryHeight).toDouble(),
                target.width.toDouble(),
                target.height.toDouble()
            )
            onMainThread { msgSend.invokeVoid(arrayOf(window, setFrame, rect, 1.toByte(), (if (animated) 1 else 0).toByte())) }
            true
        } catch (e: UnsatisfiedLinkError) {
            DesktopLifecycleLog.log("MacNsWindowFrame: AppKit call unavailable: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            DesktopLifecycleLog.log("MacNsWindowFrame: bad AppKit call: ${e.message}")
            false
        }
    }

    private fun onMainThread(block: () -> Unit) {
        lateinit var work: MainQueueWork
        work = MainQueueWork {
            try {
                block()
            } finally {
                pending.remove(work)
            }
        }
        pending.add(work)
        val mainQueue: Pointer = system.getGlobalVariableAddress("_dispatch_main_q")
        Function.getFunction("System", "dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, work))
    }
}
