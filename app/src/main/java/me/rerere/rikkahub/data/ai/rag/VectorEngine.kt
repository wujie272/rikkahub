package me.rerere.rikkahub.data.ai.rag

import kotlin.math.sqrt

/**
 * 纯 Kotlin 向量运算引擎。
 * 用户记忆规模通常 <200 条，暴力 cosine 扫描足够快（<1ms），不需要 ANN 索引。
 */
object VectorEngine {

    fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            val a = v1[i]
            val b = v2[i]
            dotProduct += a.toDouble() * b.toDouble()
            normA += a.toDouble() * a.toDouble()
            normB += b.toDouble() * b.toDouble()
        }
        return if (normA == 0.0 || normB == 0.0) 0f
        else (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    /** 序列化 FloatArray 为 JSON 数组字符串 */
    fun floatsToJson(values: List<Float>): String =
        values.joinToString(",", "[", "]") { it.toString() }

    /** 从 JSON 数组字符串反序列化为 FloatArray */
    fun jsonToFloats(json: String): List<Float>? = runCatching {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return@runCatching null
        trimmed.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().toFloat() }
    }.getOrNull()

    /**
     * 从内存列表中检索 top-K 相似记忆。
     * @return List of (id, content, score) 按相似度降序
     */
    data class SimilarResult(
        val id: Int,
        val content: String,
        val score: Float,
    )

    fun searchTopK(
        query: List<Float>,
        candidates: List<MemCandidate>,
        limit: Int = 5,
        minScore: Float = 0.35f,
    ): List<SimilarResult> {
        if (query.isEmpty() || candidates.isEmpty()) return emptyList()
        return candidates.map { c ->
            val score = c.embedding?.let { cosineSimilarity(query, it) } ?: 0f
            SimilarResult(id = c.id, content = c.content, score = score)
        }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    data class MemCandidate(
        val id: Int,
        val content: String,
        val embedding: List<Float>?,
    )
}
