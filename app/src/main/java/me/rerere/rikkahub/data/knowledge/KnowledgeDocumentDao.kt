package me.rerere.rikkahub.data.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDocumentDao {
    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NULL ORDER BY file_path, chunk_index")
    fun getByKnowledgeBaseFlow(kbId: String): Flow<List<KnowledgeDocumentEntity>>

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NULL ORDER BY file_path, chunk_index")
    suspend fun getByKnowledgeBase(kbId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :kbId AND file_path = :filePath AND deleted_at IS NULL ORDER BY chunk_index")
    suspend fun getByFilePath(kbId: String, filePath: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_document WHERE id = :id")
    suspend fun getById(id: String): KnowledgeDocumentEntity?

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :kbId AND vector IS NOT NULL AND enabled = 1 AND deleted_at IS NULL")
    suspend fun getSearchable(kbId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :kbId AND vector IS NOT NULL AND enabled = 1 AND deleted_at IS NULL AND tags LIKE '%' || :tag || '%'")
    suspend fun getSearchableByTag(kbId: String, tag: String): List<KnowledgeDocumentEntity>

    @Query("SELECT DISTINCT file_path, file_name FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NULL")
    suspend fun getDistinctFiles(kbId: String): List<FilePathAndName>

    @Query("SELECT COUNT(*) FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NULL")
    suspend fun countByKnowledgeBase(kbId: String): Int

    @Query("SELECT COUNT(DISTINCT file_path) FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NULL")
    suspend fun countFilesByKnowledgeBase(kbId: String): Int

    // ============ 软删除 / 回收站 ============

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun getDeleted(kbId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT DISTINCT file_path, file_name FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NOT NULL")
    suspend fun getDeletedFiles(kbId: String): List<FilePathAndName>

    @Query("UPDATE knowledge_document SET deleted_at = :now WHERE id = :id")
    suspend fun softDeleteById(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_document SET deleted_at = :now WHERE knowledge_base_id = :kbId AND file_path = :filePath")
    suspend fun softDeleteByFilePath(kbId: String, filePath: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_document SET deleted_at = NULL WHERE id = :id")
    suspend fun restoreById(id: String)

    @Query("UPDATE knowledge_document SET deleted_at = NULL WHERE knowledge_base_id = :kbId AND file_path = :filePath")
    suspend fun restoreByFilePath(kbId: String, filePath: String)

    @Query("DELETE FROM knowledge_document WHERE id = :id")
    suspend fun permanentlyDeleteById(id: String)

    @Query("DELETE FROM knowledge_document WHERE knowledge_base_id = :kbId AND file_path = :filePath")
    suspend fun permanentlyDeleteByFilePath(kbId: String, filePath: String)

    @Query("DELETE FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NOT NULL")
    suspend fun permanentlyDeleteAllDeleted(kbId: String)

    @Query("SELECT COUNT(*) FROM knowledge_document WHERE knowledge_base_id = :kbId AND deleted_at IS NOT NULL")
    suspend fun countDeleted(kbId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<KnowledgeDocumentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KnowledgeDocumentEntity)

    @Query("UPDATE knowledge_document SET chunk_text = :text, vector = NULL, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: String, text: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_document SET vector = :vector, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateVector(id: String, vector: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_document SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE knowledge_document SET file_name = :fileName WHERE knowledge_base_id = :kbId AND file_path = :filePath")
    suspend fun renameFile(kbId: String, filePath: String, fileName: String)

    @Query("DELETE FROM knowledge_document WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_document WHERE knowledge_base_id = :kbId AND file_path = :filePath")
    suspend fun deleteByFilePath(kbId: String, filePath: String)

    @Query("DELETE FROM knowledge_document WHERE knowledge_base_id = :kbId")
    suspend fun deleteByKnowledgeBase(kbId: String)
}

data class FilePathAndName(
    val file_path: String,
    val file_name: String,
)
