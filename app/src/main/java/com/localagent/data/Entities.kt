package com.localagent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["sessionId"])],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val body: String,
    val createdAtEpochMs: Long,
    /** Optional image attachment URI */
    val imageUri: String? = null,
    /** Hermes approval prompt id when role is Meta approval row */
    val approvalPromptId: String? = null,
)
