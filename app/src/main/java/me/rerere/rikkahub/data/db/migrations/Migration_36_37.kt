package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v36 → v37: 接入上游媒体生成（MediaCreation）三张表。
 *
 * 上游是在 v26 引入的；本地版本号已经走到 36，故在此按上游 v26 的 DDL 追加，
 * 表结构、索引、外键与上游 26.json 完全一致，级联删除行为保持相同。
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `draft` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `selected_record_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `node_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `model_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, `inputs` TEXT NOT NULL, `status` TEXT NOT NULL, `task_id` TEXT, `error` TEXT, `outputs` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)"
        )
    }
}
