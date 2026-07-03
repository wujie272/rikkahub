package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_31_32"

/**
 * v31 → v32 (Refresh schema hash after FolderEntity addition).
 *
 * This is a no-op migration that exists solely to bump the database version
 * and let Room re-compute its identity hash. The actual schema change
 * (adding the `conversation_folder` table) was done in Migration_30_31.
 *
 * Without this bump, Room's identity hash check would fail on databases
 * that were already at v31 (e.g. created via fallbackToDestructiveMigration)
 * but whose entity list has since changed (FolderEntity was added).
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: no-op hash refresh 31→32")
        // No schema changes — just bumping version to refresh Room's identity hash.
    }
}
