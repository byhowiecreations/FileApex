package com.fileapex.platform

import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.domain.clipboard.ClipboardCopySignals
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.util.concurrent.atomic.AtomicBoolean

object ClipboardShizukuAccess {
    private const val TAG = "ClipboardShizuku"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 0xFA01

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                Log.i(TAG, "binder received ready=${isReady()}")
                if (isReady()) ClipboardChangeMonitor.onShizukuReady()
            }
            Shizuku.addBinderDeadListener {
                Log.i(TAG, "binder dead")
                ClipboardChangeMonitor.onShizukuOptInChanged()
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode != PERMISSION_REQUEST_CODE) return@addRequestPermissionResultListener
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "permission result granted=$granted")
                if (granted) ClipboardChangeMonitor.onShizukuReady()
            }
        }.onFailure { error ->
            Log.w(TAG, "shizuku listeners failed :: ${error.message}")
        }
    }

    fun isInstalled(): Boolean {
        val context = androidAppContextOrNull() ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isReady(): Boolean {
        return runCatching {
            val ping = Shizuku.getBinder() != null && Shizuku.pingBinder()
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ClipboardShizukuPolicy.binderReady(ping, granted)
        }.getOrDefault(false)
    }

    fun isOptedIn(): Boolean {
        return runCatching {
            com.fileapex.di.FileApexServices.settings.clipboardShizukuEnabled.value
        }.getOrDefault(false)
    }

    fun shouldUse(): Boolean {
        return runCatching {
            val ping = Shizuku.getBinder() != null && Shizuku.pingBinder()
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ClipboardShizukuPolicy.shouldUsePrivilegedClipboard(isOptedIn(), ping, granted)
        }.getOrDefault(false)
    }

    fun isRunning(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    fun requestPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure { error ->
            Log.w(TAG, "request permission failed :: ${error.message}")
        }
    }

    fun activate() {
        start()
        runCatching {
            com.fileapex.di.FileApexServices.settings.setClipboardShizukuEnabled(true)
        }
        if (!isInstalled()) {
            openManager()
            return
        }
        if (!isRunning()) {
            BriefToast.show(com.fileapex.i18n.AppI18n.t("shizuku_step_start"))
            openManager()
            return
        }
        if (isReady()) {
            ClipboardChangeMonitor.onShizukuReady()
            return
        }
        requestPermission()
    }

    fun openManager() {
        val context = androidAppContextOrNull() ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
                .onFailure { error -> Log.w(TAG, "open shizuku failed :: ${error.message}") }
            return
        }
        val site = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://shizuku.rikka.app/")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(site) }
            .onFailure { error -> Log.w(TAG, "open shizuku site failed :: ${error.message}") }
    }

    fun tryReadText(): String? {
        if (!shouldUse()) return null
        val text = runCatching { readViaIClipboard() }
            .onFailure { error -> Log.w(TAG, "privileged clipboard read failed :: ${error.message}") }
            .getOrNull()
            ?.let { ClipboardCopySignals.usableText(it) }
        if (text != null) {
            Log.i(TAG, "privileged clipboard read (${text.length} chars)")
        }
        return text
    }

    fun tryWriteText(text: String): Boolean {
        if (!shouldUse() || text.isBlank()) return false
        return runCatching { writeViaIClipboard(text) }
            .onFailure { error -> Log.w(TAG, "privileged clipboard write failed :: ${error.message}") }
            .getOrDefault(false)
    }

    private fun readViaIClipboard(): String? {
        val clipboard = clipboardProxy() ?: return null
        for (pkg in callerPackages()) {
            val clip = invokeNamed(clipboard, "getPrimaryClip", pkg) as? ClipData ?: continue
            if (clip.itemCount <= 0) continue
            val item = clip.getItemAt(0)
            val text = ClipboardCopySignals.boundedRaw(item.text?.toString())
                ?: ClipboardCopySignals.boundedRaw(item.htmlText)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun writeViaIClipboard(text: String): Boolean {
        val clipboard = clipboardProxy() ?: return false
        val clip = ClipData.newPlainText("FileApex", text)
        return callerPackages().any { pkg ->
            invokeNamed(clipboard, "setPrimaryClip", pkg, extraFirst = clip) != null
        }
    }

    private fun callerPackages(): List<String> {
        val app = androidAppContextOrNull()?.packageName
        return listOfNotNull("com.android.shell", app)
    }

    private fun clipboardProxy(): Any? {
        if (!isReady()) return null
        val raw = systemClipboardBinder() ?: return null
        val stub = Class.forName("android.content.IClipboard\$Stub")
        // ShizukuBinderWrapper transacts as shell UID so IClipboard is not redacted without focus.
        return stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(raw))
    }

    private fun systemClipboardBinder(): IBinder? {
        val helper = runCatching {
            Class.forName("rikka.shizuku.SystemServiceHelper")
                .getMethod("getSystemService", String::class.java)
                .invoke(null, "clipboard") as? IBinder
        }.getOrNull()
        if (helper != null) return helper
        return Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as? IBinder
    }

    private fun invokeNamed(
        target: Any,
        methodName: String,
        pkg: String,
        extraFirst: Any? = null
    ): Any? {
        val userId = runCatching {
            Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as Int
        }.getOrDefault(0)
        val methods = target.javaClass.methods.filter { it.name == methodName }
        for (method in methods.sortedBy { it.parameterCount }) {
            val args = argumentsFor(method.parameterTypes, pkg, userId, extraFirst) ?: continue
            val result = runCatching { method.invoke(target, *args) }.getOrNull()
            if (result != null || method.returnType == Void.TYPE) return result ?: true
        }
        return null
    }

    private fun argumentsFor(
        types: Array<Class<*>>,
        pkg: String,
        userId: Int,
        extraFirst: Any?
    ): Array<Any?>? {
        val args = arrayOfNulls<Any>(types.size)
        var usedExtra = extraFirst == null
        for (index in types.indices) {
            val type = types[index]
            when {
                extraFirst != null && !usedExtra && type.isInstance(extraFirst) -> {
                    args[index] = extraFirst
                    usedExtra = true
                }
                type == String::class.java -> args[index] = pkg
                type == Int::class.javaPrimitiveType || type == Int::class.java -> args[index] = userId
                type.name == "android.content.AttributionSource" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
                    args[index] = attributionSource(pkg)
                }
                else -> return null
            }
        }
        if (!usedExtra) return null
        return args
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun attributionSource(pkg: String): android.content.AttributionSource {
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(Process.myUid())
        return android.content.AttributionSource.Builder(uid)
            .setPackageName(pkg)
            .build()
    }
}
