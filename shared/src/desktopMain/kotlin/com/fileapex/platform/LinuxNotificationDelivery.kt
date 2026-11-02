package com.fileapex.platform

import com.fileapex.i18n.AppI18n
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

internal class LinuxNotificationDelivery(
    private val start: (List<String>) -> Process = { args -> ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
) : DesktopNotificationDelivery {
    private class Running {
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<Process?>()
        fun stop() { cancelled.set(true); process.get()?.destroy() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicReference<Running?>()

    override fun show(title: String, body: String, done: (Boolean) -> Unit) {
        val running = Running()
        active.getAndSet(running)?.stop()
        scope.launch {
            var clicked = false
            var serverId: Long? = null
            var killer: Job? = null
            try {
                if (!running.cancelled.get()) {
                    val process = start(listOf("notify-send", "--print-id", "--wait", "--expire-time=120000", "--transient",
                        "--app-name=FileApex", "--action=default=${AppI18n.t("show_fileapex")}", "--", title, body))
                    running.process.set(process)
                    if (running.cancelled.get()) process.destroy()
                    killer = scope.launch { delay(125_000L); process.destroyForcibly() }
                    process.inputStream.use { input ->
                        serverId = input.boundedLine()?.toLongOrNull()?.takeIf { it in 1..0xFFFF_FFFFL }
                        clicked = input.boundedLine() == "default" && !running.cancelled.get()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                println("FriendsNotifier: Linux notification helper unavailable :: ${e::class.simpleName}")
            } finally {
                killer?.cancel()
                running.process.get()?.destroy()
                serverId?.let { closeNative(it) }
                active.compareAndSet(running, null)
                done(clicked && !running.cancelled.get())
            }
        }
    }

    override fun close() { active.getAndSet(null)?.stop() }
    override fun shutdown() { close(); scope.cancel() }

    private fun closeNative(id: Long) {
        try {
            val process = start(listOf("gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications", "--method", "org.freedesktop.Notifications.CloseNotification", id.toString()))
            try { process.waitFor(3, TimeUnit.SECONDS) } finally { process.destroy() }
        } catch (e: IOException) {
            println("FriendsNotifier: Linux notification dismissal unavailable :: ${e::class.simpleName}")
        }
    }

    private fun InputStream.boundedLine(): String? {
        val line = StringBuilder()
        repeat(64) {
            val byte = read()
            if (byte == -1) return line.toString().takeIf { it.isNotEmpty() }
            if (byte == 10) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        throw IOException("Oversized notification helper reply")
    }
}
