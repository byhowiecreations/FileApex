package com.fileapex.data.device

import com.fileapex.membership.MemberState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the TLS layer knows about one device. [revoked] entries are removed or departed members: their pin is kept so a
 * device that comes back with it is answered "revoked" instead of being refused as a stranger.
 */
data class TlsPinEntry(
    val deviceId: String,
    val pin: String,
    val alt: String,
    val port: Int,
    val revoked: Boolean,
    /** True when a signed event proves the pin (it is the hash of the member's key); false for a pin an older build or a pairing recorded. */
    val signed: Boolean
)

/**
 * The one place TLS pins are kept. Every reader (the TLS directory, the peer routes, the device list) and every writer
 * (pairing, the pin announcer, the user's confirmation) goes through it; it reads and writes `member_local`.
 */
interface TlsPinStore {
    suspend fun entries(): List<TlsPinEntry>
    fun observe(): Flow<List<TlsPinEntry>>

    /**
     * Records the pin, rotation pin and port of an active device, from a caller that received them over a trusted channel.
     * A signed member's pin never changes this way. A removed member takes a pin again only from a pairing ([rejoin]),
     * which makes it an active, unsigned member until a signed admission replaces that. [userConfirmed] is the user
     * trusting a changed key on screen: it replaces a signed member's pin too and leaves the member unsigned until its
     * next signed admission, so the same prompt cannot come back. False when nothing changed.
     */
    suspend fun record(deviceId: String, pin: String, port: Int, alt: String = "", rejoin: Boolean = false, userConfirmed: Boolean = false): Boolean

    /** A peer's advertised TLS port. Only the port of an active entry changes; the pin is what protects the connection. */
    suspend fun recordPort(deviceId: String, port: Int): Boolean

    object None : TlsPinStore {
        override suspend fun recordPort(deviceId: String, port: Int) = false
        override suspend fun entries(): List<TlsPinEntry> = emptyList()
        override fun observe(): Flow<List<TlsPinEntry>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun record(deviceId: String, pin: String, port: Int, alt: String, rejoin: Boolean, userConfirmed: Boolean) = false
    }

    companion object {
        val FORMAT = Regex("^[0-9a-f]{64}$")

        fun normalize(pin: String) = pin.trim().lowercase()
    }
}

class InMemoryTlsPinStore : TlsPinStore {
    private data class Row(val state: MemberState, val pin: String, val alt: String, val port: Int, val signed: Boolean)

    private val rows = MutableStateFlow<Map<String, Row>>(emptyMap())
    private val mutex = Mutex()

    fun seed(deviceId: String, pin: String, state: MemberState, signed: Boolean) {
        rows.value = rows.value + (deviceId to Row(state, pin, "", 0, signed))
    }

    private fun Map<String, Row>.toEntries() = filterValues { it.pin.isNotEmpty() }.map { (id, row) ->
        TlsPinEntry(id, row.pin, row.alt, row.port, row.state != MemberState.ACTIVE, row.signed)
    }

    override suspend fun entries() = rows.value.toEntries()
    override fun observe() = rows.map { it.toEntries() }

    override suspend fun recordPort(deviceId: String, port: Int): Boolean = mutex.withLock {
        val current = rows.value[deviceId.trim()] ?: return false
        if (current.state != MemberState.ACTIVE || port !in 1..65535 || current.port == port) return false
        rows.value = rows.value + (deviceId.trim() to current.copy(port = port))
        true
    }

    override suspend fun record(deviceId: String, pin: String, port: Int, alt: String, rejoin: Boolean, userConfirmed: Boolean): Boolean = mutex.withLock {
        val id = deviceId.trim()
        val normalizedPin = TlsPinStore.normalize(pin)
        val normalizedAlt = TlsPinStore.normalize(alt)
        if (id.isEmpty() || !TlsPinStore.FORMAT.matches(normalizedPin)) return false
        if (normalizedAlt.isNotEmpty() && !TlsPinStore.FORMAT.matches(normalizedAlt)) return false
        val current = rows.value[id]
        val returning = current != null && current.state != MemberState.ACTIVE
        if (returning && !rejoin) return false
        if (!returning && !userConfirmed && current?.signed == true && current.pin != normalizedPin) return false
        if (rows.value.any { (otherId, row) -> otherId != id && row.state == MemberState.ACTIVE && row.pin == normalizedPin }) return false
        val resolvedPort = if (port in 1..65535) port else current?.port ?: 0
        val next = Row(MemberState.ACTIVE, normalizedPin, normalizedAlt, resolvedPort, !returning && current?.signed == true && current.pin == normalizedPin)
        if (current == next) return false
        rows.value = rows.value + (id to next)
        true
    }
}
