package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_30_31"

/**
 * v30 → v31 (Add conversation_folder table + folder_id column to ConversationEntity).
 *
 * 1. Creates the `conversation_folder` table for organizing conversations into folders.
 * 2. Adds `folder_id` column to ConversationEntity (default empty string).
 *
 * The folder_id column was added to ConversationEntity in the same version so that
 * existing conversations are immediately compatible with the new folder system.
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start creating conversation_folder table + folder_id column")
        // 1. Create conversation_folder table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `conversation_folder` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `sort_index` INTEGER NOT NULL DEFAULT 0,
                `create_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`)")

        // 2. Add folder_id column to ConversationEntity
        db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''")

        Log.i(TAG, "migrate: migration 30→31 success")
    }
}
