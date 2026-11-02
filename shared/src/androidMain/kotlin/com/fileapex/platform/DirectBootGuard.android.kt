package com.fileapex.platform

import android.content.Context
import android.os.UserManager

/**
 * Whether this user's credential-encrypted storage is usable yet.
 *
 * False during the brief window after a reboot, before the user enters their PIN / pattern /
 * password for the first time this boot, even though [android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED]
 * (direct-boot-aware receivers) may already be running. Anything backed by normal
 * `SharedPreferences`, Room, or `getFilesDir()` throws `IllegalStateException` if touched while
 * this is false. Device-protected storage (`Context.createDeviceProtectedStorageContext()`,
 * see [ServiceWatchdogScheduler]) is always safe regardless of this check.
 */
fun isUserStorageUnlocked(context: Context): Boolean {
    val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
    return userManager.isUserUnlocked
}

/** Android delivers only UI_HIDDEN and the background levels since API 34; the running levels are deprecated and never arrive. */
fun trimsMemoryAt(level: Int): Boolean = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
