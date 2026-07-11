package me.rerere.rikkahub.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 知识库 Room 实体
 */
@Entity(tableName = "knowledge_base")
data class KnowledgeBaseEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    /** 嵌入模型 ID（对应 Provider 体系中的模型 UUID） */
    @ColumnInfo(name = "model_id")
    val modelId: String,
    /** 向量维度 */
    val dimensions: Int = 1536,
    /** 搜索返回的最大文档数 */
    @ColumnInfo(name = "document_count")
    val documentCount: Int = 6,
    /** 分块大小（字符数） */
    @ColumnInfo(name = "chunk_size")
    val chunkSize: Int = 1000,
    /** 块重叠（字符数） */
    @ColumnInfo(name = "chunk_overlap")
    val chunkOverlap: Int = 200,
    /** 分块策略: fixed / paragraph / markdown / code */
    @ColumnInfo(name = "chunk_strategy")
    val chunkStrategy: String = "fixed",
    /** 相似度阈值 0.0~1.0 */
    val threshold: Float = 0.35f,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
