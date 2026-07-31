package me.rerere.rikkahub.data.knowledge

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 查询向量持久化缓存
 *
 * 同一个搜索词第二次搜直接读 Room，省掉一次 embedding API 调用。
 * 老化策略：TTL + 数量上限，由 DAO 和服务层配合清理。
 */
@Entity(tableName = "query_vector_cache")
data class QueryVectorCacheEntity(
    @PrimaryKey
    val query: String,
    /** 生成该向量的模型 ID，不同模型向量不可混用 */
    val modelId: String,
    /** 向量 JSON 字符串（复用 VectorUtils 序列化/反序列化） */
    val vector: String,
    /** 创建时间戳，用于 TTL 过期判断 */
    val createdAt: Long = System.currentTimeMillis(),
)
