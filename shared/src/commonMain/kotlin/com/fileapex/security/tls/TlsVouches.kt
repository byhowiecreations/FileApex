package com.fileapex.security.tls

import java.util.concurrent.ConcurrentHashMap

/**
 * "A device of this cluster confirmed that [subjectDeviceId] now uses [pin]". Kept in memory for a day and used only
 * when the subject really presents that key, so a vouch alone never changes a stored pin.
 */
object TlsVouches {
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private data class Vouch(val pin: String, val voucherId: String, val atMs: Long)
    private val bySubject = ConcurrentHashMap<String, MutableList<Vouch>>()

    fun add(subjectDeviceId: String, pin: String, voucherId: String, nowMs: Long) {
        val list = bySubject.getOrPut(subjectDeviceId) { java.util.Collections.synchronizedList(mutableListOf()) }
        list.removeAll { it.voucherId == voucherId || nowMs - it.atMs > TTL_MS }
        list += Vouch(pin.lowercase(), voucherId, nowMs)
    }

    fun voucherFor(subjectDeviceId: String, pin: String, nowMs: Long): String? =
        bySubject[subjectDeviceId]?.toList()?.firstOrNull { it.pin == pin.lowercase() && nowMs - it.atMs <= TTL_MS }?.voucherId

    fun clear() = bySubject.clear()
}
