package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_31_32"

/**
 * v31 → v32 (Add folder_id column to ConversationEntity).
 *
 * Handles two upgrade paths:
 * 1. v30 → v31 → v32: Migration_30_31 already added folder_id, skip.
 * 2. v31 → v32 (direct): Databases created via fallbackToDestructiveMigration
 *    at v31 don't have folder_id because FolderEntity was added to the entity
 *    list after the destructive fallback. This migration adds the missing column.
 *
 * Uses PRAGMA table_info to check column existence before ALTER TABLE,
 * because SQLite throws an error if you ADD COLUMN on an existing column.
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start 31→32")

        // Check if folder_id column already exists
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

        if (hasFolderId) {
            Log.i(TAG, "folder_id already exists, skipping ALTER TABLE")
        } else {
            Log.i(TAG, "adding folder_id column to ConversationEntity")
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''")
        }

        Log.i(TAG, "migrate: migration 31→32 success")
    }
}
