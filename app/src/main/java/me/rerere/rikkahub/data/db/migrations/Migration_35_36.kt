package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v35 → v36: 为 ai_request_logs 表添加 Token 用量统计列。
 *
 * 新增列：
 * - input_tokens: 输入 Token 数
 * - output_tokens: 输出 Token 数
 * - total_tokens: 总 Token 数
 * - cost: 请求花费（USD）
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ai_request_logs` ADD COLUMN `input_tokens` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `ai_request_logs` ADD COLUMN `output_tokens` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `ai_request_logs` ADD COLUMN `total_tokens` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `ai_request_logs` ADD COLUMN `cost` REAL NOT NULL DEFAULT 0")
    }
}
