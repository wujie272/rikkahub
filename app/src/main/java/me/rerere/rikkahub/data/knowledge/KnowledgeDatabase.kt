package me.rerere.rikkahub.data.knowledge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        KnowledgeBaseEntity::class,
        KnowledgeDocumentEntity::class,
        MountedKnowledgeDir::class,
        QueryVectorCacheEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao
    abstract fun mountedKnowledgeDirDao(): MountedKnowledgeDirDao
    abstract fun queryVectorCacheDao(): QueryVectorCacheDao

    companion object {
        @Volatile
        private var INSTANCE: KnowledgeDatabase? = null

        fun getInstance(context: Context): KnowledgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KnowledgeDatabase::class.java,
                    "rikka_knowledge.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE knowledge_document ADD COLUMN deleted_at INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mounted_knowledge_dir` (
                        `id` TEXT NOT NULL,
                        `kb_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `tree_uri` TEXT NOT NULL,
                        `posix_path` TEXT,
                        `file_count` INTEGER NOT NULL DEFAULT 0,
                        `total_size_bytes` INTEGER NOT NULL DEFAULT 0,
                        `last_sync_at` INTEGER NOT NULL DEFAULT 0,
                        `auto_sync` INTEGER NOT NULL DEFAULT 1,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mounted_knowledge_dir_kb_id` ON `mounted_knowledge_dir` (`kb_id`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `query_vector_cache` (
                        `query` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `vector` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`query`)
                    )
                """)
            }
        }
    }
}
