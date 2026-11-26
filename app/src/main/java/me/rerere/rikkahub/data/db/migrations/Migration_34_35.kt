package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ai_request_logs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `latency_ms` INTEGER,
                `duration_ms` INTEGER,
                `source` TEXT NOT NULL,
                `provider_name` TEXT NOT NULL,
                `provider_type` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `model_display_name` TEXT NOT NULL,
                `stream` INTEGER NOT NULL,
                `params_json` TEXT NOT NULL,
                `request_messages_json` TEXT NOT NULL,
                `request_url` TEXT NOT NULL DEFAULT '',
                `request_preview` TEXT NOT NULL,
                `response_preview` TEXT NOT NULL,
                `response_text` TEXT NOT NULL DEFAULT '',
                `response_raw_text` TEXT NOT NULL DEFAULT '',
                `error` TEXT
            )
            """.trimIndent()
        )
    }
}
