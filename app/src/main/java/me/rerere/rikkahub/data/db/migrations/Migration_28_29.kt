package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_28_29"

/**
 * v28 → v29 (Vector memory support).
 *
 * Adds columns to the `memoryentity` table:
 * - `embedding` TEXT (nullable) — JSON float array
 * - `embedding_model_id` TEXT (nullable, default "")
 * - `type` INTEGER (default 0) — 0=CORE, 1=EPISODIC
 * - `pinned` INTEGER (default 0)
 * - `created_at` INTEGER (default 0)
 * - `last_accessed_at` INTEGER (default 0)
 *
 * All new columns are additive with defaults — backwards compatible.
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start adding vector memory columns")
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `embedding` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `embedding_model_id` TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `type` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `last_accessed_at` INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migration 28→29 success")
        } finally {
            db.endTransaction()
        }
    }
}
