package com.fileapex.friends.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A person on this device's friends allowlist. Per device, never synced; the key is the friends Iroh key, not a cluster key. */
@Entity(tableName = "friend_links")
data class FriendLinkEntity(
    @PrimaryKey val friendKey: String,
    val label: String,
    /** Friends, Family, Co-Workers or a typed name. A label only. */
    val groupName: String,
    val addedAtMs: Long,
    /** Subfolder of @inbox that holds this friend's files; follows the label, tied to [friendKey]. */
    val folderName: String,
    /** True when this friend's files are accepted without asking. */
    val autoAccept: Boolean = false
)

/** An invite this device created. Only the hash of the secret is kept; it burns after use, expiry or too many failures. */
@Entity(tableName = "friend_invites")
data class FriendInviteEntity(
    @PrimaryKey val inviteId: String,
    val secretHash: String,
    val groupName: String,
    val expiresAtMs: Long,
    val failedAttempts: Int = 0,
    val used: Boolean = false
)

/** One offered or received file. [receivedBytes] is how much of the quarantine file is verified on disk, for resume. */
@Entity(tableName = "friend_incoming")
data class FriendIncomingEntity(
    @PrimaryKey val transferId: String,
    val friendKey: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** OFFERED, ACCEPTED, RECEIVED, REFUSED or FAILED. */
    val state: String,
    val receivedBytes: Long = 0L,
    val quarantinePath: String = "",
    val createdAtMs: Long
)

/** Reachable only from the friends handler; the structure guard test fails if any other package names it. */
@Dao
interface FriendsDao {
    @Query("SELECT * FROM friend_links ORDER BY addedAtMs ASC, friendKey ASC")
    fun observeLinks(): Flow<List<FriendLinkEntity>>

    @Query("SELECT * FROM friend_links WHERE friendKey = :key")
    suspend fun link(key: String): FriendLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLink(link: FriendLinkEntity)

    @Query("UPDATE friend_links SET autoAccept = :autoAccept WHERE friendKey = :key")
    suspend fun setAutoAccept(key: String, autoAccept: Boolean)

    @Query("DELETE FROM friend_links WHERE friendKey = :key")
    suspend fun removeLink(key: String)

    @Query("DELETE FROM friend_incoming WHERE friendKey = :key")
    suspend fun removeIncomingOf(key: String)

    @Query("SELECT * FROM friend_invites WHERE inviteId = :id")
    suspend fun invite(id: String): FriendInviteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putInvite(invite: FriendInviteEntity)

    @Query("DELETE FROM friend_invites WHERE used = 1 OR expiresAtMs < :nowMs")
    suspend fun purgeInvites(nowMs: Long)

    @Query("SELECT * FROM friend_incoming WHERE transferId = :id")
    suspend fun incoming(id: String): FriendIncomingEntity?

    @Query("SELECT * FROM friend_incoming ORDER BY createdAtMs DESC")
    fun observeIncoming(): Flow<List<FriendIncomingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putIncoming(row: FriendIncomingEntity)
}

/** The label is sealed on disk, so the list is ordered here and not by SQL. */
fun Flow<List<FriendLinkEntity>>.byLabel(): Flow<List<FriendLinkEntity>> =
    map { list -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }) }
