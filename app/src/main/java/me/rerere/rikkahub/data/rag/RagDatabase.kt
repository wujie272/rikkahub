package me.rerere.rikkahub.data.rag

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RagDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDAO
}
