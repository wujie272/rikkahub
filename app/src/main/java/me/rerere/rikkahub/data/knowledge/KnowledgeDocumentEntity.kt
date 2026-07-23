package me.rerere.rikkahub.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库文档块实体
 * 每个文档被分块后，每块一行记录，含向量。
 */
@Entity(
    tableName = "knowledge_document",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_base_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("knowledge_base_id"),
        Index("knowledge_base_id", "file_path"),
    ]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "knowledge_base_id")
    val knowledgeBaseId: String,
    /** 源文件路径 / 标识 */
    @ColumnInfo(name = "file_path")
    val filePath: String = "",
    /** 文件名 */
    @ColumnInfo(name = "file_name")
    val fileName: String = "",
    /** 块索引 */
    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int = 0,
    /** 块文本内容 */
    @ColumnInfo(name = "chunk_text")
    val chunkText: String,
    /** 标签（逗号分隔） */
    val tags: String = "",
    /** 向量 — JSON Float 数组字符串，如 "[0.123,0.456,...]" */
    val vector: String? = null,
    /** 是否启用（参与搜索） */
    val enabled: Boolean = true,
    /** 软删除时间戳，null = 未删除 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
