package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_27_28"

/**
 * v27 → v28 (Phase 5C: Group round-robin index persistence).
 *
 * Adds `next_round_robin_index` column to the `groups` table
 * (INTEGER NOT NULL DEFAULT 0).
 *
 * This is an additive, backwards-compatible change — existing rows
 * automatically get the default value of 0.
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start adding next_round_robin_index to groups table")
        db.beginTransaction()
        try {
            db.execSQL(
                """
                ALTER TABLE `groups`
                ADD COLUMN `next_round_robin_index` INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migration 27→28 success")
        } finally {
            db.endTransaction()
        }
    }
}
