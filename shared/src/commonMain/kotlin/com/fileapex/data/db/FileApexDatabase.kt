package com.fileapex.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.DatabaseView
import androidx.room3.Database
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.fileapex.data.bulletin.BulletinBoardTransactionDao
import com.fileapex.data.bulletin.ChecklistItemDao
import com.fileapex.data.bulletin.ChecklistItemEntity
import com.fileapex.data.bulletin.MessageDao
import com.fileapex.data.bulletin.MessageFlagDao
import com.fileapex.data.bulletin.MessageFlagEntity
import com.fileapex.data.bulletin.MessageEntity
import com.fileapex.data.bulletin.OutboxDao
import com.fileapex.data.bulletin.OutboxEntity
import com.fileapex.data.bulletin.ProcessedPacketDao
import com.fileapex.data.bulletin.ProcessedPacketEntity
import com.fileapex.data.bulletin.TombstoneDao
import com.fileapex.data.bulletin.TombstoneEntity
import com.fileapex.friends.data.FriendIncomingEntity
import com.fileapex.friends.data.FriendInviteEntity
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.data.FriendsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@DatabaseView(viewName = RemovedDevicesView.NAME, value = RemovedDevicesView.SQL)
@Serializable
data class RemovedDeviceEntity(
    val deviceId: String,
    val publicKeyHash: String,
    val lastKnownIp: String,
    val port: Int,
    /** Epoch millis when the user removed this node (UTC). */
    val removedAtEpochMs: Long,
    /** [com.fileapex.domain.pairing.RemovalReason.code] the removal was made with; empty from builds that did not record one. */
    val reason: String = "",
    /** The device that made the removal, empty when unknown. */
    val removedBy: String = ""
)

@DatabaseView(viewName = PairedDevicesView.NAME, value = PairedDevicesView.SQL)
@Serializable
data class PairedDeviceEntity(
    val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String,
    val publicKey: String = "",
    val e2eeEnabled: Boolean = false,
    val rootPath: String,
    val clientVersion: String = "",
    val clientVersionCode: Int = 0,
    val platform: String = "",
    /** OS slug: android, macos, windows, linux. */
    val os: String = "",
    val deviceMake: String = "",
    val deviceModel: String = "",
    val supportedProtocolsJson: String = "[]",
    /** Epoch millis when this peer was last observed online (UTC). */
    val lastSeenEpochMs: Long = 0L,
    val cardPosX: Float? = null,
    val cardPosY: Float? = null,
    val cardSortOrder: Int = 0,
    val cardMenuOrder: String = "",
    val tilePosX: Float? = null,
    val tilePosY: Float? = null,
    val tileSortOrder: Int = 0,
    val tileMenuOrder: String = "",
    val clusterVersion: Long = 0L,
    val isRemoved: Boolean = false,
    val removedAt: Long? = null,
    /** MagicDNS label confirmed against /api/v1/identity. Empty until that check passes. */
    val tailnetHostname: String = "",
    /** Tailnet IPv4 confirmed against /api/v1/identity. Never copied into lastKnownIp. */
    val tailnetIpv4: String = "",
    /** SPKI SHA-256 (hex) of this peer's TLS key. Empty until learned over an authenticated channel. */
    val tlsPin: String = "",
    /** Second accepted pin while a key change is being confirmed. */
    val tlsPinAlt: String = "",
    /** Peer's TLS listener port; 0 when unknown. */
    val tlsPort: Int = 0,
    /** Salted hash of the peer's OS machine id (see HardwareId); a hint that two ids are one device. Empty when unknown. */
    val hardwareHash: String = ""
)

interface DeviceDao {
    @Query("SELECT * FROM paired_devices_view WHERE isRemoved = 0 ORDER BY deviceId ASC")
    fun getAllDevicesUnordered(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices_view WHERE isRemoved = 0 ORDER BY deviceId ASC")
    suspend fun getAllDevicesOnceUnordered(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices_view WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_devices_view WHERE isRemoved = 1")
    suspend fun getTombstonedDevices(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices_view ORDER BY deviceId ASC")
    suspend fun getAllDevicesIncludingTombstonesUnordered(): List<PairedDeviceEntity>

    suspend fun upsertDevice(device: PairedDeviceEntity)

    /** Writes the profile columns only; membership (state, pin, key hash) is never touched. */
    suspend fun upsertProfile(device: PairedDeviceEntity) = upsertDevice(device)

    @Query("UPDATE member_profiles SET deviceName = :deviceName WHERE deviceId = :deviceId")
    suspend fun renameDevice(deviceId: String, deviceName: String)

    @Query("UPDATE member_profiles SET lastKnownIp = :ip, port = :port WHERE deviceId = :deviceId")
    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int)

    @Query(
        "UPDATE member_profiles SET lastSeenEpochMs = :epochMs, lastKnownIp = :ip, port = :port " +
            "WHERE deviceId = :deviceId"
    )
    suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long)

    @Query("UPDATE member_profiles SET lastSeenEpochMs = :epochMs WHERE deviceId = :deviceId")
    suspend fun touchLastSeenEpoch(deviceId: String, epochMs: Long)

    @Query(
        "UPDATE member_profiles SET tailnetHostname = :hostname, tailnetIpv4 = :ipv4 WHERE deviceId = :deviceId"
    )
    suspend fun updateTailnet(deviceId: String, hostname: String, ipv4: String)

    @Query(
        "UPDATE member_profiles SET cardPosX = :x, cardPosY = :y, cardSortOrder = :order, cardMenuOrder = :menuOrder " +
            "WHERE deviceId = :deviceId"
    )
    suspend fun updateCardLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String)

    @Query(
        "UPDATE member_profiles SET tilePosX = :x, tilePosY = :y, tileSortOrder = :order, tileMenuOrder = :menuOrder " +
            "WHERE deviceId = :deviceId"
    )
    suspend fun updateTileLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String)

    @Query("DELETE FROM member_profiles WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Query("DELETE FROM member_profiles")
    suspend fun deleteAllDevices()

    suspend fun insertRemovedDevice(device: RemovedDeviceEntity)

    suspend fun clearRemovedDevice(deviceId: String)

    suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String)

    suspend fun getAllRemovedDevices(): List<RemovedDeviceEntity>

    suspend fun deleteAllRemovedDevices()

    /** The old cluster ended: every local fact and pre-chain removal of a device other than [keepId] goes. */
    suspend fun forgetLocalExcept(keepId: String)
}

/** The device table as Room builds it: the writes that reach `member_profiles` and `members` live here, so fakes of [DeviceDao] stay small. */
@Dao
interface RoomDeviceDao : DeviceDao, MemberStore {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProfile(profile: MemberProfileEntity)

    /**
     * Writes the profile. A tombstone also marks the member removed (never over a removal that already exists).
     * The TLS pin is never written here; it belongs to the pin store. Membership authority is the chain.
     */
    override suspend fun upsertProfile(device: PairedDeviceEntity) = putProfile(device.toProfile())

    @Transaction
    override suspend fun upsertDevice(device: PairedDeviceEntity) {
        putProfile(device.toProfile())
        if (device.isRemoved) recordRemoval(device.deviceId, device.publicKeyHash.trim(), "", "")
    }

    // A removal is the chain's record when the chain names the device; an id the chain never named is kept in `legacy_removed`.
    override suspend fun insertRemovedDevice(device: RemovedDeviceEntity) =
        recordRemoval(device.deviceId, device.publicKeyHash.trim(), device.reason, device.removedBy)

    @Query(
        "INSERT INTO member_local (deviceId, keyHash) VALUES (:deviceId, :keyHash) " +
            "ON CONFLICT(deviceId) DO UPDATE SET keyHash = CASE WHEN excluded.keyHash != '' THEN excluded.keyHash ELSE member_local.keyHash END"
    )
    suspend fun putKeyHash(deviceId: String, keyHash: String)

    @Query(
        "INSERT INTO legacy_removed (deviceId, keyHash, reason, removedBy) VALUES (:deviceId, :keyHash, :reason, :by) " +
            "ON CONFLICT(deviceId) DO UPDATE SET keyHash = CASE WHEN excluded.keyHash != '' THEN excluded.keyHash ELSE legacy_removed.keyHash END, " +
            "reason = CASE WHEN legacy_removed.reason != '' THEN legacy_removed.reason ELSE excluded.reason END, " +
            "removedBy = CASE WHEN legacy_removed.removedBy != '' THEN legacy_removed.removedBy ELSE excluded.removedBy END"
    )
    suspend fun putLegacyRemoved(deviceId: String, keyHash: String, reason: String, by: String)

    @Query("UPDATE member_local SET pendingAdmitAt = 0 WHERE deviceId = :deviceId")
    suspend fun dropPendingAdmit(deviceId: String)

    @Query("SELECT COUNT(*) FROM membership_chain WHERE subject = :deviceId")
    suspend fun chainNames(deviceId: String): Int

    @Transaction
    suspend fun recordRemoval(deviceId: String, keyHash: String, reason: String, by: String) {
        if (keyHash.isNotEmpty()) putKeyHash(deviceId, keyHash)
        if (chainNames(deviceId) == 0) {
            dropPendingAdmit(deviceId)
            putLegacyRemoved(deviceId, keyHash, reason, by)
        }
        refreshMembers()
    }

    @Query("DELETE FROM legacy_removed WHERE deviceId = :deviceId")
    suspend fun deleteLegacyRemoved(deviceId: String)

    @Query("DELETE FROM legacy_removed WHERE keyHash = :keyHash AND :keyHash != ''")
    suspend fun deleteLegacyRemovedByKeyHash(keyHash: String)

    @Query("DELETE FROM legacy_removed")
    suspend fun deleteAllLegacyRemoved()

    @Transaction
    override suspend fun clearRemovedDevice(deviceId: String) {
        deleteLegacyRemoved(deviceId)
        refreshMembers()
    }

    @Transaction
    override suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String) {
        deleteLegacyRemovedByKeyHash(publicKeyHash)
        refreshMembers()
    }

    @Query("SELECT * FROM removed_devices_view")
    override suspend fun getAllRemovedDevices(): List<RemovedDeviceEntity>

    @Transaction
    override suspend fun deleteAllRemovedDevices() {
        deleteAllLegacyRemoved()
        refreshMembers()
    }

    @Query("DELETE FROM member_local WHERE deviceId != :keepId")
    suspend fun deleteLocalExcept(keepId: String)

    @Query("DELETE FROM legacy_removed WHERE deviceId != :keepId")
    suspend fun deleteLegacyRemovedExcept(keepId: String)

    @Transaction
    override suspend fun forgetLocalExcept(keepId: String) {
        deleteLocalExcept(keepId)
        deleteLegacyRemovedExcept(keepId)
        refreshMembers()
    }
}

/** The name is sealed on disk, so these lists are ordered here (case-insensitively, by name, then id) and not by SQL. */
private val byDeviceName = compareBy<PairedDeviceEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.deviceName }.thenBy { it.deviceId }

fun DeviceDao.getAllDevices(): Flow<List<PairedDeviceEntity>> =
    getAllDevicesUnordered().map { list -> list.sortedWith(byDeviceName) }

suspend fun DeviceDao.getAllDevicesOnce(): List<PairedDeviceEntity> = getAllDevicesOnceUnordered().sortedWith(byDeviceName)

suspend fun DeviceDao.getAllDevicesIncludingTombstones(): List<PairedDeviceEntity> =
    getAllDevicesIncludingTombstonesUnordered().sortedWith(byDeviceName)

@Database(
    entities = [
        PendingTransferEntity::class,
        MessageEntity::class,
        TombstoneEntity::class,
        OutboxEntity::class,
        ProcessedPacketEntity::class,
        MessageFlagEntity::class,
        ChecklistItemEntity::class,
        FriendLinkEntity::class,
        FriendInviteEntity::class,
        FriendIncomingEntity::class,
        MemberEntity::class,
        MemberLocalEntity::class,
        LegacyRemovedEntity::class,
        MembershipMetaEntity::class,
        ChainRecordEntity::class,
        MemberProfileEntity::class
    ],
    version = 28,
    views = [PairedDeviceEntity::class, RemovedDeviceEntity::class, MemberEffectiveEntity::class]
)
@ConstructedBy(FileApexDatabaseConstructor::class)
abstract class FileApexDatabase : RoomDatabase() {
    abstract fun deviceDao(): RoomDeviceDao
    abstract fun tlsPinDao(): TlsPinDao
    abstract fun membershipDao(): MembershipDao
    abstract fun chainDao(): ChainDao
    abstract fun pendingTransferDao(): PendingTransferDao
    abstract fun messageDao(): MessageDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun outboxDao(): OutboxDao
    abstract fun processedPacketDao(): ProcessedPacketDao
    abstract fun transactionDao(): BulletinBoardTransactionDao
    abstract fun messageFlagDao(): MessageFlagDao
    abstract fun checklistItemDao(): ChecklistItemDao
    abstract fun friendsDao(): FriendsDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object FileApexDatabaseConstructor : RoomDatabaseConstructor<FileApexDatabase> {
    override fun initialize(): FileApexDatabase
}

expect class RoomDbBuilder {
    fun builder(): RoomDatabase.Builder<FileApexDatabase>
}
