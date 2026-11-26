package me.rerere.rikkahub.data.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MountedKnowledgeDirDao {

    @Query("SELECT * FROM mounted_knowledge_dir WHERE kb_id = :kbId ORDER BY created_at DESC")
    fun getByKbId(kbId: String): List<MountedKnowledgeDir>

    @Query("SELECT * FROM mounted_knowledge_dir WHERE id = :id")
    suspend fun getById(id: String): MountedKnowledgeDir?

    @Query("SELECT * FROM mounted_knowledge_dir WHERE kb_id = :kbId ORDER BY created_at DESC")
    fun observeByKbId(kbId: String): Flow<List<MountedKnowledgeDir>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dir: MountedKnowledgeDir)

    @Update
    suspend fun update(dir: MountedKnowledgeDir)

    @Query("DELETE FROM mounted_knowledge_dir WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM mounted_knowledge_dir WHERE kb_id = :kbId")
    suspend fun deleteByKbId(kbId: String)

    @Query("""
        UPDATE mounted_knowledge_dir 
        SET file_count = :fileCount, total_size_bytes = :totalSizeBytes, last_sync_at = :lastSyncAt 
        WHERE id = :id
    """)
    suspend fun updateSyncStats(id: String, fileCount: Int, totalSizeBytes: Long, lastSyncAt: Long)

    @Query("SELECT COUNT(*) FROM mounted_knowledge_dir WHERE kb_id = :kbId")
    suspend fun countByKbId(kbId: String): Int
}
