package me.rerere.rikkahub.data.knowledge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        KnowledgeBaseEntity::class,
        KnowledgeDocumentEntity::class,
    ],
    version = 1,
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
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
