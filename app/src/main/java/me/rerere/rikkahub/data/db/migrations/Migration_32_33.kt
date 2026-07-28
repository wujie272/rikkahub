package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_32_33"

/**
 * v32 → v33 (Add folder_id column + conversation_folder table, safely).
 *
 * This migration re-applies the schema changes from the original Migration_30_31,
 * using IF NOT EXISTS / safe checks so it works regardless of whether the previous
 * migrations actually ran on this database.
 *
 * Background: Migration_30_31 was supposed to add the `folder_id` column to
 * ConversationEntity and create the `conversation_folder` table, but on some
 * devices the database was already at v32 (via destructive fallback) and the
 * 30→31 migration never executed. The no-op Migration_31_32 then bumped to v32
 * without the column, causing Room's identity hash check to fail.
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start adding folder_id column + conversation_folder table")
        db.beginTransaction()
        try {
            // 1. Create conversation_folder table (safe: IF NOT EXISTS)
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

            // 2. Add folder_id column to ConversationEntity (safe: check if column exists)
            val cursor = db.query("PRAGMA table_info('ConversationEntity')")
            var hasFolderId = false
            while (cursor.moveToNext()) {
                val colName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (colName == "folder_id") {
                    hasFolderId = true
                    break
                }
            }
            cursor.close()

            if (!hasFolderId) {
                db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''")
                Log.i(TAG, "migrate: added folder_id column")
            } else {
                Log.i(TAG, "migrate: folder_id column already exists, skipping")
            }

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migration 32→33 success")
        } finally {
            db.endTransaction()
        }
    }
}
