package me.rerere.rikkahub.data.ai.requestlog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_request_logs")
data class AIRequestLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "latency_ms")
    val latencyMs: Long?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "provider_name")
    val providerName: String,
    @ColumnInfo(name = "provider_type")
    val providerType: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "model_display_name")
    val modelDisplayName: String,
    @ColumnInfo(name = "stream")
    val stream: Boolean,
    @ColumnInfo(name = "params_json")
    val paramsJson: String,
    @ColumnInfo(name = "request_messages_json")
    val requestMessagesJson: String,
    @ColumnInfo(name = "request_url", defaultValue = "")
    val requestUrl: String,
    @ColumnInfo(name = "request_preview")
    val requestPreview: String,
    @ColumnInfo(name = "response_preview")
    val responsePreview: String,
    @ColumnInfo(name = "response_text", defaultValue = "")
    val responseText: String,
    @ColumnInfo(name = "response_raw_text", defaultValue = "")
    val responseRawText: String,
    @ColumnInfo(name = "error")
    val error: String?,
    // 以下为 v36 新增：Token 用量统计
    @ColumnInfo(name = "input_tokens", defaultValue = "0")
    val inputTokens: Int = 0,
    @ColumnInfo(name = "output_tokens", defaultValue = "0")
    val outputTokens: Int = 0,
    @ColumnInfo(name = "total_tokens", defaultValue = "0")
    val totalTokens: Int = 0,
    @ColumnInfo(name = "cost", defaultValue = "0")
    val cost: Double = 0.0,
)
