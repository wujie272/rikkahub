package me.rerere.rikkahub.data.ai.requestlog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
