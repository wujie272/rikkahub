package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)

    @Query("UPDATE memoryentity SET embedding = :embedding, embedding_model_id = :modelId WHERE id = :id")
    suspend fun updateEmbedding(id: Int, embedding: String?, modelId: String?)

    @Query("SELECT embedding_model_id FROM memoryentity WHERE embedding IS NOT NULL AND embedding_model_id IS NOT NULL LIMIT :limit")
    suspend fun getMemoriesWithModelIds(limit: Int = 1): List<String?>

    @Query("UPDATE memoryentity SET pinned = :pinned WHERE id = :id")
    suspend fun updatePin(id: Int, pinned: Boolean)

    @Query("SELECT * FROM memoryentity WHERE content LIKE '%' || :query || '%' ORDER BY pinned DESC, id DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity ORDER BY pinned DESC, id DESC")
    suspend fun getAllMemoriesSorted(): List<MemoryEntity>
}
