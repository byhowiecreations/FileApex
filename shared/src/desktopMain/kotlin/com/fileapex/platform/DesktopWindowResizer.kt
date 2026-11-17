package com.fileapex.platform

import com.fileapex.ui.motion.FileApexMotion
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/** Moves a desktop window to a frame without driving `WindowState` per frame. */
interface DesktopWindowResizer {
    /** Goes to [target] (AWT units), at once when [reduced]; [onSettled] runs once on the EDT when the window is there. A later call replaces this one. */
    fun animateTo(window: Window, target: WindowFrame, reduced: Boolean, onSettled: () -> Unit)

    companion object {
        fun create(scope: CoroutineScope): DesktopWindowResizer {
            val fallback = DesktopAwtWindowAnimator(scope)
            return if (DesktopPlatformPaths.isMacOs()) DesktopMacWindowAnimator(scope, fallback) else fallback
        }
    }
}

/** The screen area of the display the window is on, without the dock, menu bar or taskbar. */
object DesktopUsableScreen {
    fun forWindow(window: Window): WindowFrame {
        val config = window.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return DesktopWindowExpansion.usable(
            WindowFrame(bounds.x.toFloat(), bounds.y.toFloat(), bounds.width.toFloat(), bounds.height.toFloat()),
            FrameInsets(insets.top.toFloat(), insets.left.toFloat(), insets.bottom.toFloat(), insets.right.toFloat())
        )
    }
}

/** Windows, Linux and the macOS fallback: one coroutine on the EDT sets the AWT bounds directly at about 60 fps. */
internal class DesktopAwtWindowAnimator(private val scope: CoroutineScope) : DesktopWindowResizer {
    private var job: Job? = null

    override fun animateTo(window: Window, target: WindowFrame, reduced: Boolean, onSettled: () -> Unit) {
        job?.cancel()
        job = scope.launch(Dispatchers.Swing) {
            val durationMillis = FileApexMotion.windowMoveMillis(reduced)
            val from = WindowFrame(window.x.toFloat(), window.y.toFloat(), window.width.toFloat(), window.height.toFloat())
            val startNanos = System.nanoTime()
            while (true) {
                val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000.0
                val fraction = if (durationMillis == 0L) 1f else (elapsedMillis / durationMillis).toFloat().coerceIn(0f, 1f)
                val eased = WindowEasing.at(fraction)
                window.setBounds(
                    mix(from.x, target.x, eased).roundToInt(),
                    mix(from.y, target.y, eased).roundToInt(),
                    mix(from.width, target.width, eased).roundToInt(),
                    mix(from.height, target.height, eased).roundToInt()
                )
                if (fraction >= 1f) break
                delay(FRAME_MILLIS)
            }
            onSettled()
        }
    }

    private fun mix(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}

/** macOS: AppKit animates the frame (`setFrame:display:animate:` through JNA); [fallback] runs when the window cannot be reached. */
internal class DesktopMacWindowAnimator(
    private val scope: CoroutineScope,
    private val fallback: DesktopWindowResizer
) : DesktopWindowResizer {
    private var job: Job? = null

    override fun animateTo(window: Window, target: WindowFrame, reduced: Boolean, onSettled: () -> Unit) {
        job?.cancel()
        val started = MacNsWindowFrame.animate(window, target, animated = !reduced)
        if (!started) {
            fallback.animateTo(window, target, reduced, onSettled)
            return
        }
        job = scope.launch(Dispatchers.Swing) {
            val deadline = System.nanoTime() + SETTLE_TIMEOUT_MILLIS * 1_000_000
            while (!reached(window, target) && System.nanoTime() < deadline) {
                try {
                    delay(POLL_MILLIS)
                } catch (e: CancellationException) {
                    throw e
                }
            }
            onSettled()
        }
    }

    private fun reached(window: Window, target: WindowFrame): Boolean =
        kotlin.math.abs(window.width - target.width) <= 1f && kotlin.math.abs(window.height - target.height) <= 1f &&
            kotlin.math.abs(window.x - target.x) <= 1f && kotlin.math.abs(window.y - target.y) <= 1f

    private companion object {
        const val POLL_MILLIS = 20L
        const val SETTLE_TIMEOUT_MILLIS = 800L
    }
}
