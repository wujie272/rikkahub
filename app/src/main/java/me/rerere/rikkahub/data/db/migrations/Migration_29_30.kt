package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_29_30"

/**
 * v29 → v30 (Remove Telegram bot feature).
 *
 * Drops the `telegram_chat` table that stored Telegram chat mappings.
 * This table is no longer needed after the Telegram bot feature was removed.
 */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start dropping telegram_chat table")
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS `telegram_chat`")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migration 29→30 success")
        } finally {
            db.endTransaction()
        }
    }
}
