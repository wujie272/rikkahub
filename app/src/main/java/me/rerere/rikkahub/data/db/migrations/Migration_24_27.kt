package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_24_27"

/**
 * v24 → v27 (combined manual migration replacing removed auto-migrations).
 *
 * This combines three upstream/fork version steps that were previously AutoMigrations:
 *   24→25: Adds custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id
 *          columns to ConversationEntity (all with default values, so ALTER TABLE is safe).
 *   25→26: Creates the workspaces table (from upstream 2.3.1 merge).
 *   26→27: Group round-robin index (no schema change; handled by Migration_27_28+).
 *
 * These were converted to a manual migration because Room's KSP cannot validate
 * auto-migrations when later versions (27→30) use manual migrations — it can't
 * compute intermediate entity states through the manual gap.
 */
val Migration_24_27 = object : Migration(24, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start 24→27 (add ConversationEntity columns + workspaces table)")
        db.beginTransaction()
        try {
            // 24→25: ConversationEntity columns (all have defaults → safe ALTER TABLE)
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `custom_system_prompt` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `mode_injection_ids` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `lorebook_ids` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `workspace_cwd` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''")

            // 25→26: WorkspaceEntity table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `workspaces` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `root` TEXT NOT NULL,
                    `shell_status` TEXT NOT NULL DEFAULT 'DISABLED',
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `last_access_at` INTEGER NOT NULL DEFAULT 0,
                    `tool_approvals` TEXT NOT NULL DEFAULT '[]',
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_workspaces_root` ON `workspaces` (`root`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_workspaces_updated_at` ON `workspaces` (`updated_at`)")

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: 24→27 success")
        } finally {
            db.endTransaction()
        }
    }
}
