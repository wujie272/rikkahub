package me.rerere.rikkahub.data.knowledge

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeBaseDao {
    @Query("SELECT * FROM knowledge_base ORDER BY updated_at DESC")
    fun getAllFlow(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_base ORDER BY updated_at DESC")
    suspend fun getAll(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_base WHERE id = :id")
    suspend fun getById(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KnowledgeBaseEntity)

    @Update
    suspend fun update(entity: KnowledgeBaseEntity)

    @Delete
    suspend fun delete(entity: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_base WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM knowledge_base")
    suspend fun count(): Int
}
