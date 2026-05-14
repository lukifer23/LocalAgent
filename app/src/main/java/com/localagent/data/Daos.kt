package com.localagent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAtEpochMs DESC")
    fun observe(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatSessionEntity): Long

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    fun observe(sessionId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChatMessageEntity>): List<Long>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: String): ChatMessageEntity?

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
