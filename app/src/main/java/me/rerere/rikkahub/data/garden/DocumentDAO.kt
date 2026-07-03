package me.rerere.rikkahub.data.garden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDAO {
    @Query("SELECT * FROM document ORDER BY updated_at DESC")
    fun getAllFlow(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM document")
    suspend fun getAll(): List<DocumentEntity>

    @Query("SELECT * FROM document WHERE file_path = :path ORDER BY chunk_index")
    suspend fun getByFilePath(path: String): List<DocumentEntity>

    @Query("SELECT * FROM document WHERE id = :id")
    suspend fun getById(id: Int): DocumentEntity?

    @Query("SELECT file_hash FROM document WHERE file_path = :path LIMIT 1")
    suspend fun getFileHash(path: String): String?

    @Query("SELECT COUNT(*) FROM document")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT file_path) FROM document")
    suspend fun countFiles(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DocumentEntity>)

    @Query("DELETE FROM document WHERE file_path = :path")
    suspend fun deleteByFilePath(path: String)

    @Query("DELETE FROM document WHERE file_path IN (:paths)")
    suspend fun deleteByFilePaths(paths: List<String>)

    @Query("SELECT DISTINCT file_path FROM document")
    suspend fun getAllFilePaths(): List<String>

    @Query("DELETE FROM document")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT source_folder FROM document ORDER BY source_folder")
    suspend fun getDistinctFolders(): List<String>

    @Query("SELECT * FROM document ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<DocumentEntity>

    // 获取所有有效的带 embedding 的数据（用于搜索）
    @Query("SELECT * FROM document WHERE embedding IS NOT NULL")
    suspend fun getAllWithEmbedding(): List<DocumentEntity>

    // 按文件夹过滤
    @Query("SELECT * FROM document WHERE source_folder = :folder AND embedding IS NOT NULL")
    suspend fun getByFolderWithEmbedding(folder: String): List<DocumentEntity>

    // 按文件夹统计文件数量
    @Query("SELECT source_folder, COUNT(DISTINCT file_path) AS file_count FROM document GROUP BY source_folder ORDER BY file_count DESC")
    suspend fun getFolderFileCounts(): List<FolderStat>
}



data class FolderStat(
    val source_folder: String,
    val file_count: Int,
)
