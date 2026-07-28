package me.rerere.rikkahub.data.knowledge

import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 向量工具函数
 */
object VectorUtils {
    private val json = Json { ignoreUnknownKeys = true }

    /** 向量转 JSON 字符串 */
    fun vectorToJson(vector: List<Float>): String = json.encodeToString(vector)

    /** JSON 字符串转向量 */
    fun jsonToVector(jsonStr: String): List<Float> {
        return try {
            json.decodeFromString<List<Double>>(jsonStr).map { it.toFloat() }
        } catch (_: Exception) {
            try {
                json.decodeFromString<List<Float>>(jsonStr)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** 余弦相似度 */
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dotProduct / denom).toFloat()
    }
}
