package com.fileapex.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import com.fileapex.data.device.TlsPinEntry
import com.fileapex.data.device.TlsPinStore
import com.fileapex.membership.MemberState
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The TLS facts of the master roster: how this device trusts and reaches each member, kept in `member_local`. */
@Dao
interface TlsPinDao : MemberStore {
    @Query("SELECT * FROM member_effective WHERE length(tlsPin) = 64 ORDER BY deviceId ASC")
    suspend fun pinned(): List<MemberEffectiveEntity>

    @Query("SELECT * FROM member_effective WHERE length(tlsPin) = 64 ORDER BY deviceId ASC")
    fun observePinned(): Flow<List<MemberEffectiveEntity>>

    @Query("SELECT * FROM member_effective WHERE deviceId = :deviceId LIMIT 1")
    suspend fun member(deviceId: String): MemberEffectiveEntity?

    @Query("SELECT COUNT(*) FROM member_effective WHERE state = 'ACTIVE' AND tlsPin = :pin AND deviceId != :deviceId")
    suspend fun otherActiveWithPin(pin: String, deviceId: String): Int

    @Query("UPDATE member_local SET tlsPort = :port WHERE deviceId = :deviceId AND tlsPort != :port AND deviceId IN (SELECT deviceId FROM members WHERE state = 'ACTIVE')")
    suspend fun setPort(deviceId: String, port: Int): Int

    @Query("SELECT COUNT(*) FROM membership_chain WHERE subject = :deviceId")
    suspend fun chainNames(deviceId: String): Int

    @Query(
        "INSERT INTO member_local (deviceId, tlsPin, tlsPinAlt, tlsPort) VALUES (:deviceId, :pin, :alt, :port) " +
            "ON CONFLICT(deviceId) DO UPDATE SET tlsPin = excluded.tlsPin, tlsPinAlt = excluded.tlsPinAlt, tlsPort = excluded.tlsPort"
    )
    suspend fun putTls(deviceId: String, pin: String, alt: String, port: Int)

    /** A key-less peer paired here: its pin, and the admit this device still owes the chain. */
    @Query(
        "INSERT INTO member_local (deviceId, tlsPin, tlsPinAlt, tlsPort, pendingAdmitAt) VALUES (:deviceId, :pin, :alt, :port, :now) " +
            "ON CONFLICT(deviceId) DO UPDATE SET tlsPin = excluded.tlsPin, tlsPinAlt = excluded.tlsPinAlt, tlsPort = excluded.tlsPort, pendingAdmitAt = excluded.pendingAdmitAt"
    )
    suspend fun putPending(deviceId: String, pin: String, alt: String, port: Int, now: Long)

    /** A removed device paired in person again: its pin, an admit owed to the chain, and the hold that keeps a removal already in the chain from undoing it. */
    @Query(
        "INSERT INTO member_local (deviceId, tlsPin, tlsPinAlt, tlsPort, pendingAdmitAt, pairedAtEpochMs, pairedChainSeq) VALUES (:deviceId, :pin, :alt, :port, :now, :now, :seq) " +
            "ON CONFLICT(deviceId) DO UPDATE SET tlsPin = excluded.tlsPin, tlsPinAlt = excluded.tlsPinAlt, tlsPort = excluded.tlsPort, " +
            "pendingAdmitAt = excluded.pendingAdmitAt, pairedAtEpochMs = excluded.pairedAtEpochMs, pairedChainSeq = excluded.pairedChainSeq"
    )
    suspend fun putReturning(deviceId: String, pin: String, alt: String, port: Int, now: Long, seq: Long)

    @Query("DELETE FROM legacy_removed WHERE deviceId = :deviceId")
    suspend fun forgetLegacyRemoval(deviceId: String)

    @Transaction
    suspend fun setTls(deviceId: String, pin: String, alt: String, port: Int) {
        putTls(deviceId, pin, alt, port)
        refreshMembers()
    }

    /** A device this one has no member row for: a chain member that has not been projected yet only gets its pin, any other is a peer paired here that the chain does not know. */
    @Transaction
    suspend fun addMember(deviceId: String, pin: String, alt: String, port: Int, now: Long) {
        if (chainNames(deviceId) > 0) putTls(deviceId, pin, alt, port) else putPending(deviceId, pin, alt, port, now)
        refreshMembers()
    }

    @Transaction
    suspend fun reactivate(deviceId: String, pin: String, alt: String, port: Int, now: Long) {
        putReturning(deviceId, pin, alt, port, now, chainView().state.seq)
        forgetLegacyRemoval(deviceId)
        refreshMembers()
    }
}

class RoomTlsPinStore(private val dao: TlsPinDao, private val now: () -> Long = TimeUtils::now) : TlsPinStore {
    private val writer = Mutex()

    override suspend fun entries(): List<TlsPinEntry> = dao.pinned().map { it.toEntry() }

    override fun observe(): Flow<List<TlsPinEntry>> = dao.observePinned().map { rows -> rows.map { it.toEntry() } }

    override suspend fun recordPort(deviceId: String, port: Int): Boolean = writer.withLock {
        port in 1..65535 && dao.setPort(deviceId.trim(), port) > 0
    }

    override suspend fun record(deviceId: String, pin: String, port: Int, alt: String, rejoin: Boolean, userConfirmed: Boolean): Boolean = writer.withLock {
        val id = deviceId.trim()
        val normalizedPin = TlsPinStore.normalize(pin)
        val normalizedAlt = TlsPinStore.normalize(alt)
        if (id.isEmpty() || !TlsPinStore.FORMAT.matches(normalizedPin)) return@withLock false
        if (normalizedAlt.isNotEmpty() && !TlsPinStore.FORMAT.matches(normalizedAlt)) return@withLock false
        val row = dao.member(id)
        val returning = row != null && row.state != MemberState.ACTIVE.name
        if (returning && !rejoin) return@withLock false
        if (dao.otherActiveWithPin(normalizedPin, id) > 0) return@withLock false
        val resolvedPort = if (port in 1..65535) port else row?.tlsPort ?: 0
        if (row != null && returning) {
            dao.reactivate(id, normalizedPin, normalizedAlt, resolvedPort, now())
            return@withLock true
        }
        if (row == null) {
            dao.addMember(id, normalizedPin, normalizedAlt, resolvedPort, now())
            return@withLock true
        }
        if (row.tlsPin == normalizedPin && row.tlsPinAlt == normalizedAlt && row.tlsPort == resolvedPort) return@withLock false
        dao.setTls(id, normalizedPin, normalizedAlt, resolvedPort)
        true
    }
}

private fun MemberEffectiveEntity.toEntry() = TlsPinEntry(
    deviceId = deviceId,
    pin = tlsPin,
    alt = tlsPinAlt,
    port = tlsPort,
    revoked = state != MemberState.ACTIVE.name,
    signed = false
)
