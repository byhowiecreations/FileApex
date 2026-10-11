package com.fileapex.data.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import com.fileapex.data.device.CandidateOrigin
import com.fileapex.data.device.PeerCandidate
import com.fileapex.data.device.PeerCandidateStore
import kotlinx.serialization.json.Json

@Entity(tableName = "peer_candidates")
data class PeerCandidateEntity(
    @PrimaryKey val deviceId: String,
    val introducedBy: String,
    val origin: String,
    val introducedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val attempts: Int,
    val lastAttemptEpochMs: Long,
    val deviceJson: String
)

@Dao
interface PeerCandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(candidate: PeerCandidateEntity)

    @Query("SELECT * FROM peer_candidates WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): PeerCandidateEntity?

    @Query("SELECT * FROM peer_candidates ORDER BY introducedAtEpochMs ASC")
    suspend fun all(): List<PeerCandidateEntity>

    @Query("DELETE FROM peer_candidates WHERE deviceId = :deviceId")
    suspend fun remove(deviceId: String)

    @Query("DELETE FROM peer_candidates WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun removeExpired(nowEpochMs: Long)

    @Query("DELETE FROM peer_candidates")
    suspend fun clear()
}

class RoomPeerCandidateStore(private val dao: PeerCandidateDao) : PeerCandidateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun put(candidate: PeerCandidate) = dao.put(candidate.toEntity())
    override suspend fun get(deviceId: String): PeerCandidate? = dao.get(deviceId)?.toCandidate()
    override suspend fun all(): List<PeerCandidate> = dao.all().mapNotNull { it.toCandidate() }
    override suspend fun remove(deviceId: String) = dao.remove(deviceId)
    override suspend fun removeExpired(nowEpochMs: Long) = dao.removeExpired(nowEpochMs)
    override suspend fun clear() = dao.clear()

    private fun PeerCandidate.toEntity() = PeerCandidateEntity(
        deviceId = deviceId,
        introducedBy = introducedBy,
        origin = origin.name,
        introducedAtEpochMs = introducedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        attempts = attempts,
        lastAttemptEpochMs = lastAttemptEpochMs,
        deviceJson = json.encodeToString(PairedDeviceEntity.serializer(), device)
    )

    private fun PeerCandidateEntity.toCandidate(): PeerCandidate? {
        val device = runCatching { json.decodeFromString(PairedDeviceEntity.serializer(), deviceJson) }.getOrNull()
            ?: return null
        val kind = CandidateOrigin.entries.firstOrNull { it.name == origin } ?: return null
        return PeerCandidate(device, introducedBy, kind, introducedAtEpochMs, expiresAtEpochMs, attempts, lastAttemptEpochMs)
    }
}
