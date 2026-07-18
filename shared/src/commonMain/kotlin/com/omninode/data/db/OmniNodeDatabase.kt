package com.omninode.data.db

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

@Entity(tableName = "paired_devices")
@Serializable
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String,
    val rootPath: String
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

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)
}

@Database(entities = [PairedDeviceEntity::class], version = 2)
@ConstructedBy(OmniNodeDatabaseConstructor::class)
abstract class OmniNodeDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object OmniNodeDatabaseConstructor : RoomDatabaseConstructor<OmniNodeDatabase> {
    override fun initialize(): OmniNodeDatabase
}

expect class RoomDbBuilder {
    fun builder(): RoomDatabase.Builder<OmniNodeDatabase>
}
