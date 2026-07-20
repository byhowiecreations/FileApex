package com.omninode.session

import com.omninode.di.OmniNodeServices
import com.omninode.util.TimeUtils
import com.omninode.util.TimestampDiagnostics

/**
 * Thread-safe, timestamp-based browse unlock sessions for PIN-protected peers.
 *
 * Idle expiry is evaluated on demand from wall-clock epoch millis (Doze/sleep safe).
 * File transfers intentionally do **not** consult this manager.
 */
object DeviceSessionManager {
    private val lock = Any()
    private val lastAccessedByDeviceId = mutableMapOf<String, Long>()

    /**
     * @return true when this peer may be browsed without re-entering PIN.
     */
    fun isSessionValid(deviceId: String): Boolean {
        synchronized(lock) {
            val lastAccessed = lastAccessedByDeviceId[deviceId] ?: return false
            val timeoutMs = OmniNodeServices.settings.pinIdleTimeout.value.toMillis()
            if (timeoutMs <= 0L) {
                // Immediate: valid until explicitly cleared (return Home).
                return true
            }
            return TimeUtils.isWithinWindow(lastAccessed, timeoutMs)
        }
    }

    /** Refresh last-access time after successful unlock or active folder navigation. */
    fun markDeviceAccessed(deviceId: String) {
        synchronized(lock) {
            lastAccessedByDeviceId[deviceId] =
                TimestampDiagnostics.mutatingNow("DeviceSessionManager.lastAccessed[$deviceId]")
        }
    }

    /** Drop browse unlock (e.g. user returned to the device list). */
    fun clearSession(deviceId: String) {
        synchronized(lock) {
            lastAccessedByDeviceId.remove(deviceId)
        }
    }

    fun clearAllSessions() {
        synchronized(lock) {
            lastAccessedByDeviceId.clear()
        }
    }
}
