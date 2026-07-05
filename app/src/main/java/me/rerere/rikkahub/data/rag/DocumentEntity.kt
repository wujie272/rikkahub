package me.rerere.rikkahub.data.rag

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 笔记分块实体（独立数据库，不与主库耦合）。
 * embedding 存为 JSON Float 数组字符串："[0.123, 0.456, ...]"
 */
@Entity(tableName = "document")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("file_path")
    val filePath: String, // 文件绝对路径
    @ColumnInfo("file_modified_at")
    val fileModifiedAt: Long, // 文件修改时间戳
    @ColumnInfo("file_hash")
    val fileHash: String, // SHA-256 文件内容哈希
    @ColumnInfo("chunk_index")
    val chunkIndex: Int, // 在文件中的第几个块
    @ColumnInfo("chunk_text")
    val chunkText: String, // 块文本内容
    @ColumnInfo("embedding")
    val embedding: String? = null, // JSON Float 数组，null 表示尚未计算
    @ColumnInfo(name = "embedding_model_id", defaultValue = "")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "source_folder", defaultValue = "")
    val sourceFolder: String = "", // 来源文件夹名，用于筛选
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
)
