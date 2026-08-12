package me.rerere.rikkahub.data.ai.requestlog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** v36 新增：仪表盘聚合统计 POJO。 */
data class AiUsageSnapshot(
    @ColumnInfo(name = "totalCount") val totalCount: Long,
    @ColumnInfo(name = "successCount") val successCount: Long,
    @ColumnInfo(name = "failedCount") val failedCount: Long,
    @ColumnInfo(name = "totalInputTokens") val totalInputTokens: Long,
    @ColumnInfo(name = "totalOutputTokens") val totalOutputTokens: Long,
    @ColumnInfo(name = "totalTokens") val totalTokens: Long,
    @ColumnInfo(name = "totalCost") val totalCost: Double,
    @ColumnInfo(name = "avgDurationMs") val avgDurationMs: Long,
)

/** v36 新增：按模型聚合的用量 POJO。 */
data class AiModelUsage(
    @ColumnInfo(name = "modelDisplayName") val modelDisplayName: String,
    @ColumnInfo(name = "modelCount") val modelCount: Long,
    @ColumnInfo(name = "modelCost") val modelCost: Double,
)

@Dao
interface AIRequestLogDao {
    @Insert
    fun insert(log: AIRequestLogEntity): Long

    @Query("SELECT COUNT(*) FROM ai_request_logs")
    fun countAll(): Int

    @Query("SELECT * FROM ai_request_logs ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AIRequestLogEntity>>

    @Query("SELECT * FROM ai_request_logs ORDER BY created_at DESC LIMIT :limit")
    fun getRecent(limit: Int): List<AIRequestLogEntity>

    @Query("SELECT * FROM ai_request_logs ORDER BY created_at DESC")
    fun getAll(): List<AIRequestLogEntity>

    @Query("SELECT * FROM ai_request_logs WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<AIRequestLogEntity?>

    @Query("UPDATE ai_request_logs SET source = :source WHERE id = :id")
    fun updateSource(id: Long, source: String)

    @Query("DELETE FROM ai_request_logs")
    fun clearAll()

    @Query("DELETE FROM ai_request_logs WHERE id = :id")
    fun deleteById(id: Long)

    /**
     * 仪表盘聚合统计：按时间段聚合调用次数 / 成功失败 / Token 合计 / 花费 / 平均耗时。
     * [sinceMs] 为时间窗口起点（含），[untilMs] 为终点（不含）。
     */
    @Query("""
        SELECT
            COUNT(*) AS totalCount,
            SUM(CASE WHEN error IS NULL THEN 1 ELSE 0 END) AS successCount,
            SUM(CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END) AS failedCount,
            COALESCE(SUM(input_tokens), 0) AS totalInputTokens,
            COALESCE(SUM(output_tokens), 0) AS totalOutputTokens,
            COALESCE(SUM(total_tokens), 0) AS totalTokens,
            COALESCE(SUM(cost), 0.0) AS totalCost,
            COALESCE(AVG(duration_ms), 0) AS avgDurationMs
        FROM ai_request_logs
        WHERE created_at >= :sinceMs AND created_at < :untilMs
    """)
    fun usageSnapshot(sinceMs: Long, untilMs: Long): AiUsageSnapshot?

    /** 汇总区间内所有调用记录 SQL —— 供按模型聚合使用。 */
    @Query("""
        SELECT
            model_display_name AS modelDisplayName,
            COUNT(*) AS modelCount,
            COALESCE(SUM(cost), 0.0) AS modelCost
        FROM ai_request_logs
        WHERE created_at >= :sinceMs AND created_at < :untilMs
        GROUP BY model_display_name
        ORDER BY modelCount DESC
        LIMIT :limit
    """)
    fun usageByModel(sinceMs: Long, untilMs: Long, limit: Int = 6): List<AiModelUsage>

    @Query("""
        DELETE FROM ai_request_logs 
        WHERE id NOT IN (
            SELECT id FROM ai_request_logs
            ORDER BY created_at DESC
            LIMIT :keep
        )
    """)
    fun pruneKeepLatest(keep: Int)
}
