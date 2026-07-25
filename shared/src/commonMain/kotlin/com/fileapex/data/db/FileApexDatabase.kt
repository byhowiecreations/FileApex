package com.fileapex.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(tableName = "removed_devices")
@Serializable
data class RemovedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val publicKeyHash: String,
    val lastKnownIp: String,
    val port: Int,
    /** Epoch millis when the user removed this node (UTC). */
    val removedAtEpochMs: Long
)

@Entity(tableName = "paired_devices")
@Serializable
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String,
    val rootPath: String,
    val clientVersion: String = "",
    val clientVersionCode: Int = 0,
    val platform: String = "",
    val supportedProtocolsJson: String = "[]",
    /** Epoch millis when this peer was last observed online (UTC). */
    val lastSeenEpochMs: Long = 0L
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY deviceName COLLATE NOCASE ASC")
    fun getAllDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices ORDER BY deviceName COLLATE NOCASE ASC")
    suspend fun getAllDevicesOnce(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET deviceName = :deviceName WHERE deviceId = :deviceId")
    suspend fun renameDevice(deviceId: String, deviceName: String)

    @Query("UPDATE paired_devices SET lastKnownIp = :ip, port = :port WHERE deviceId = :deviceId")
    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int)

    @Query(
        "UPDATE paired_devices SET lastSeenEpochMs = :epochMs, lastKnownIp = :ip, port = :port " +
            "WHERE deviceId = :deviceId"
    )
    suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long)

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemovedDevice(device: RemovedDeviceEntity)

    @Query("SELECT COUNT(*) FROM removed_devices WHERE deviceId = :deviceId")
    suspend fun countRemovedById(deviceId: String): Int

    @Query(
        "SELECT COUNT(*) FROM removed_devices " +
            "WHERE publicKeyHash = :publicKeyHash AND publicKeyHash != ''"
    )
    suspend fun countRemovedByPublicKeyHash(publicKeyHash: String): Int

    @Query("DELETE FROM removed_devices WHERE deviceId = :deviceId")
    suspend fun clearRemovedDevice(deviceId: String)

    @Query(
        "DELETE FROM removed_devices " +
            "WHERE publicKeyHash = :publicKeyHash AND publicKeyHash != ''"
    )
    suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String)
}

@Database(entities = [PairedDeviceEntity::class, RemovedDeviceEntity::class], version = 4)
@ConstructedBy(FileApexDatabaseConstructor::class)
abstract class FileApexDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object FileApexDatabaseConstructor : RoomDatabaseConstructor<FileApexDatabase> {
    override fun initialize(): FileApexDatabase
}

expect class RoomDbBuilder {
    fun builder(): RoomDatabase.Builder<FileApexDatabase>
}
