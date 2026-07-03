package me.rerere.rikkahub.data.grove

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GroveDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDAO
}
