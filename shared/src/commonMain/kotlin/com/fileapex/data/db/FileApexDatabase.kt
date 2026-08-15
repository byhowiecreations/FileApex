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
import com.fileapex.data.note.NoteRecord
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
    val lastSeenEpochMs: Long = 0L
)

@Entity(tableName = "note_records")
@Serializable
data class NoteEntity(
    @PrimaryKey val noteId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val content: String,
    val driveFileId: String? = null,
    val checksum: String? = null,
    val epochMs: Long,
    val isMine: Boolean,
    val attachmentFileName: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val attachmentPinned: Boolean = false,
    val attachmentLocalPath: String? = null
)

fun NoteEntity.toRecord(): NoteRecord = NoteRecord(
    noteId = noteId,
    sourceDeviceId = sourceDeviceId,
    sourceDeviceName = sourceDeviceName,
    content = content,
    driveFileId = driveFileId,
    checksum = checksum,
    epochMs = epochMs,
    isMine = isMine,
    attachmentFileName = attachmentFileName,
    attachmentSizeBytes = attachmentSizeBytes,
    attachmentPinned = attachmentPinned,
    attachmentLocalPath = attachmentLocalPath
)

fun NoteRecord.toEntity(): NoteEntity = NoteEntity(
    noteId = noteId,
    sourceDeviceId = sourceDeviceId,
    sourceDeviceName = sourceDeviceName,
    content = content,
    driveFileId = driveFileId,
    checksum = checksum,
    epochMs = epochMs,
    isMine = isMine,
    attachmentFileName = attachmentFileName,
    attachmentSizeBytes = attachmentSizeBytes,
    attachmentPinned = attachmentPinned,
    attachmentLocalPath = attachmentLocalPath
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

@Dao
interface ControlDeliveryDao {
    @Query("SELECT COUNT(*) FROM pending_control_deliveries")
    suspend fun countPending(): Int

    @Query("DELETE FROM pending_control_deliveries")
    suspend fun deleteAll()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM note_records ORDER BY epochMs ASC")
    fun observeAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note_records ORDER BY epochMs ASC")
    suspend fun getAllNotesOnce(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Query("DELETE FROM note_records WHERE noteId = :noteId")
    suspend fun deleteNote(noteId: String)

    @Query("SELECT COUNT(*) FROM note_records WHERE noteId = :noteId OR (checksum IS NOT NULL AND checksum = :checksum AND checksum != '')")
    suspend fun countNoteOrChecksum(noteId: String, checksum: String?): Int
}

@Database(
    entities = [
        PairedDeviceEntity::class,
        RemovedDeviceEntity::class,
        PendingControlDeliveryEntity::class,
        PendingTransferEntity::class,
        NoteEntity::class
    ],
    version = 10
)
@ConstructedBy(FileApexDatabaseConstructor::class)
abstract class FileApexDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun controlDeliveryDao(): ControlDeliveryDao
    abstract fun pendingTransferDao(): PendingTransferDao
    abstract fun noteDao(): NoteDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object FileApexDatabaseConstructor : RoomDatabaseConstructor<FileApexDatabase> {
    override fun initialize(): FileApexDatabase
}

expect class RoomDbBuilder {
    fun builder(): RoomDatabase.Builder<FileApexDatabase>
}
