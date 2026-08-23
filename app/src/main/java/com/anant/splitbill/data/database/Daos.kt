package com.anant.splitbill.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomDao {
    @Query("SELECT * FROM rooms ORDER BY createdAtEpochMs ASC")
    fun observeRooms(): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE id = :id LIMIT 1")
    suspend fun getRoom(id: String): RoomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(room: RoomEntity)

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM rooms")
    suspend fun count(): Int
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE roomId = :roomId ORDER BY sortOrder ASC")
    fun observeMembers(roomId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE roomId = :roomId ORDER BY sortOrder ASC")
    suspend fun getMembers(roomId: String): List<MemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<MemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: MemberEntity)

    @Query("DELETE FROM members WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM members WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: String)
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE roomId = :roomId ORDER BY timestampEpochMs ASC, id ASC")
    fun observeEntries(roomId: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE roomId = :roomId ORDER BY timestampEpochMs ASC, id ASC")
    suspend fun getEntries(roomId: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE roomId = :roomId ORDER BY timestampEpochMs DESC, id DESC LIMIT :limit")
    fun observeRecent(roomId: String, limit: Int = 50): Flow<List<EntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<EntryEntity>)

    @Query("DELETE FROM entries WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM entries WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: String)

    @Query(
        """
        SELECT * FROM entries
        WHERE roomId = :roomId
        ORDER BY timestampEpochMs DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun latest(roomId: String): EntryEntity?

    @Transaction
    suspend fun replaceAllForRoom(roomId: String, entries: List<EntryEntity>) {
        deleteForRoom(roomId)
        if (entries.isNotEmpty()) insertAll(entries)
    }
}
