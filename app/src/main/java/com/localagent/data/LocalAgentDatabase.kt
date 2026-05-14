package com.localagent.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LocalAgentDatabase : RoomDatabase() {
    abstract fun sessions(): ChatSessionDao

    abstract fun messages(): ChatMessageDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE chat_messages ADD COLUMN approvalPromptId TEXT DEFAULT NULL",
                    )
                }
            }
    }
}
