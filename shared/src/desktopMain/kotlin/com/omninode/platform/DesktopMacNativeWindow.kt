package com.omninode.platform

import java.awt.Component
import java.awt.Window

/** Resolves the AppKit NSWindow pointer for a Compose/AWT desktop window (macOS only). */
object DesktopMacNativeWindow {
    fun nsWindowPointer(window: Window): Long? {
        if (!System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
            return null
        }
        return runCatching { resolvePointer(window) }
            .getOrNull()
    }

    private fun resolvePointer(window: Window): Long {
        val peer = resolvePeer(window)
            ?: error("AWT window peer unavailable")
        val platformWindow = peer.javaClass.methods
            .firstOrNull { it.name == "getPlatformWindow" && it.parameterCount == 0 }
            ?.invoke(peer)
            ?: peer

        val pointerMethods = listOf(
            "getLayerPtr",
            "getNSWindowPtr",
            "getNSWindow",
            "getContentWindow"
        )
        for (methodName in pointerMethods) {
            val method = platformWindow.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?: continue
            when (val value = method.invoke(platformWindow)) {
                is Long -> if (value != 0L) return value
                is Number -> {
                    val longValue = value.toLong()
                    if (longValue != 0L) return longValue
                }
            }
        }

        readPtrField(platformWindow)?.let { return it }

        error("NSWindow pointer not found on ${platformWindow.javaClass.name}")
    }

    private fun readPtrField(platformWindow: Any): Long? {
        var type: Class<*> = platformWindow.javaClass
        while (type != Any::class.java) {
            val value = runCatching {
                val field = type.getDeclaredField("ptr")
                field.isAccessible = true
                field.getLong(platformWindow)
            }.getOrNull()
            if (value != null && value != 0L) return value
            type = type.superclass ?: break
        }
        return null
    }

    private fun resolvePeer(component: Component): Any? {
        resolvePeerViaAwtAccessor(component)?.let { return it }
        component.javaClass.methods
            .firstOrNull { it.name == "getPeer" && it.parameterCount == 0 }
            ?.let { method ->
                runCatching { method.invoke(component) }.getOrNull()?.let { return it }
            }
        return runCatching {
            val peerField = Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            peerField.get(component)
        }.getOrNull()
    }

    private fun resolvePeerViaAwtAccessor(component: Component): Any? {
        return runCatching {
            val accessorClass = Class.forName("sun.awt.AWTAccessor")
            val componentAccessor = accessorClass.getMethod("getComponentAccessor").invoke(null)
            componentAccessor.javaClass.getMethod("getPeer", Component::class.java)
                .invoke(componentAccessor, component)
        }.getOrNull()
    }
}
