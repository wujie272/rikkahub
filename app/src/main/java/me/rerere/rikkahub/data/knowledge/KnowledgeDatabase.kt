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
    ],
    version = 3,
    exportSchema = false,
)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao

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
                    .addMigrations(MIGRATION_2_3)
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
    }
}
