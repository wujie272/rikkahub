package me.rerere.rikkahub.data.garden

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GardenDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDAO
}
