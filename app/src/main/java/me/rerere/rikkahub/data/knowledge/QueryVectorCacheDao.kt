package me.rerere.rikkahub.data.knowledge

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface QueryVectorCacheDao {
    /** 按 query + modelId 精确查询缓存 */
    @Query("SELECT * FROM query_vector_cache WHERE `query` = :query AND modelId = :modelId")
    suspend fun getByQuery(query: String, modelId: String): QueryVectorCacheEntity?

    /** 写入或更新缓存（Upsert = 存在则更新，不存在则插入） */
    @Upsert
    suspend fun upsert(cache: QueryVectorCacheEntity)

    /** 删除指定时间戳之前的所有过期缓存 */
    @Query("DELETE FROM query_vector_cache WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** 只保留最近 N 条，删掉更老的 */
    @Query("""
        DELETE FROM query_vector_cache WHERE `query` NOT IN (
            SELECT `query` FROM query_vector_cache ORDER BY createdAt DESC LIMIT :maxCount
        )
    """)
    suspend fun keepTop(maxCount: Int)
}
