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
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

object ClipboardShizukuAccess {
    private const val TAG = "ClipboardShizuku"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 0xFA01

    private val started = AtomicBoolean(false)

    private val shizukuClass: Class<*>? by lazy {
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
    }

    fun start() {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return
        if (!started.compareAndSet(false, true)) return
        val clazz = shizukuClass ?: return
        val loader = clazz.classLoader ?: return
        runCatching {
            val receivedInterface = Class.forName("rikka.shizuku.Shizuku\$OnBinderReceivedListener", true, loader)
            val receivedProxy = Proxy.newProxyInstance(loader, arrayOf(receivedInterface)) { _, method, _ ->
                if (method.name == "onBinderReceived") {
                    Log.i(TAG, "binder received ready=${isReady()}")
                    if (isReady()) ClipboardChangeMonitor.onShizukuReady()
                }
                null
            }
            clazz.getMethod("addBinderReceivedListenerSticky", receivedInterface).invoke(null, receivedProxy)

            val deadInterface = Class.forName("rikka.shizuku.Shizuku\$OnBinderDeadListener", true, loader)
            val deadProxy = Proxy.newProxyInstance(loader, arrayOf(deadInterface)) { _, method, _ ->
                if (method.name == "onBinderDead") {
                    Log.i(TAG, "binder dead")
                    ClipboardChangeMonitor.onShizukuOptInChanged()
                }
                null
            }
            clazz.getMethod("addBinderDeadListener", deadInterface).invoke(null, deadProxy)

            val permissionInterface = Class.forName("rikka.shizuku.Shizuku\$OnRequestPermissionResultListener", true, loader)
            val permissionProxy = Proxy.newProxyInstance(loader, arrayOf(permissionInterface)) { _, method, args ->
                if (method.name == "onRequestPermissionResult" && args != null && args.size >= 2) {
                    val requestCode = args[0] as? Int ?: 0
                    val grantResult = args[1] as? Int ?: PackageManager.PERMISSION_DENIED
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        Log.i(TAG, "permission result granted=$granted")
                        if (granted) ClipboardChangeMonitor.onShizukuReady()
                    }
                }
                null
            }
            clazz.getMethod("addRequestPermissionResultListener", permissionInterface).invoke(null, permissionProxy)
        }.onFailure { error ->
            Log.w(TAG, "shizuku listeners failed :: ${error.message}")
        }
    }

    fun isInstalled(): Boolean {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return false
        val context = androidAppContextOrNull() ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isReady(): Boolean {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return false
        return runCatching {
            val ping = getBinder() != null && pingBinder()
            val granted = checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ClipboardShizukuPolicy.binderReady(ping, granted)
        }.getOrDefault(false)
    }

    fun isOptedIn(): Boolean {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return false
        return runCatching {
            com.fileapex.di.FileApexServices.settings.clipboardShizukuEnabled.value
        }.getOrDefault(false)
    }

    fun shouldUse(): Boolean {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return false
        return runCatching {
            val ping = getBinder() != null && pingBinder()
            val granted = checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ClipboardShizukuPolicy.shouldUsePrivilegedClipboard(isOptedIn(), ping, granted)
        }.getOrDefault(false)
    }

    fun isRunning(): Boolean {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return false
        return runCatching { pingBinder() }.getOrDefault(false)
    }

    fun requestPermission() {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return
        runCatching {
            if (!pingBinder()) return
            if (checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            invokeRequestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure { error ->
            Log.w(TAG, "request permission failed :: ${error.message}")
        }
    }

    fun activate() {
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return
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
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild) return
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
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild || !shouldUse()) return null
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
        if (com.fileapex.di.FileApexServices.isPlayStoreBuild || !shouldUse() || text.isBlank()) return false
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
        val wrapped = wrapBinder(raw) ?: return null
        val stub = Class.forName("android.content.IClipboard\$Stub")
        // ShizukuBinderWrapper transacts as shell UID so IClipboard is not redacted without focus.
        return stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)
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

    private fun wrapBinder(raw: IBinder): IBinder? {
        return runCatching {
            val wrapperClass = Class.forName("rikka.shizuku.ShizukuBinderWrapper")
            wrapperClass.getConstructor(IBinder::class.java).newInstance(raw) as? IBinder
        }.getOrNull()
    }

    private fun pingBinder(): Boolean {
        val clazz = shizukuClass ?: return false
        return runCatching {
            clazz.getMethod("pingBinder").invoke(null) as? Boolean
        }.getOrNull() ?: false
    }

    private fun getBinder(): IBinder? {
        val clazz = shizukuClass ?: return null
        return runCatching {
            clazz.getMethod("getBinder").invoke(null) as? IBinder
        }.getOrNull()
    }

    private fun checkSelfPermission(): Int {
        val clazz = shizukuClass ?: return PackageManager.PERMISSION_DENIED
        return runCatching {
            clazz.getMethod("checkSelfPermission").invoke(null) as? Int
        }.getOrNull() ?: PackageManager.PERMISSION_DENIED
    }

    private fun invokeRequestPermission(code: Int) {
        val clazz = shizukuClass ?: return
        clazz.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, code)
    }

    private fun getUid(): Int {
        val clazz = shizukuClass ?: return Process.myUid()
        return runCatching {
            clazz.getMethod("getUid").invoke(null) as? Int
        }.getOrNull() ?: Process.myUid()
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
        val uid = getUid()
        return android.content.AttributionSource.Builder(uid)
            .setPackageName(pkg)
            .build()
    }
}
