package me.rerere.rikkahub.data.grove

import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * 纯 Kotlin BM25 索引，用于关键词检索。
 *
 * 与语义搜索互补：BM25 擅长精确关键词匹配（专有名词、代码、编号），
 * 语义搜索擅长同义/近义匹配。两者加权融合效果最佳。
 *
 * 使用 BM25+（带 term frequency 上限的变体），对短文本更友好。
 * 全部纯 Kotlin 实现，零外部依赖。
 */
class Bm25Index(
    /** 所有文档的文本列表，build() 时传入 */
    private val documents: List<String> = emptyList(),
    /** BM25 参数：控制 term frequency 饱和度，默认 1.2 */
    private val k1: Float = 1.2f,
    /** BM25 参数：控制文档长度归一化，默认 0.75 */
    private val b: Float = 0.75f,
    /** BM25+ 参数：给零频词一个极小的基础分，默认 0.5 */
    private val delta: Float = 0.5f,
) {
    /** 文档总数 */
    private var totalDocs: Int = 0
    /** 平均文档长度（词数） */
    private var avgDocLength: Double = 0.0
    /** 每个文档的词频表 [docIdx -> (term -> count)] */
    private var termFreqs: List<Map<String, Int>> = emptyList()
    /** 每个词出现在多少文档中 */
    private var docFreq: Map<String, Int> = emptyMap()
    /** 是否已构建 */
    private var built: Boolean = false

    /**
     * 构建 BM25 索引。
     * 必须在 search() 前调用。
     */
    fun build(): Bm25Index {
        if (built) return this
        totalDocs = documents.size
        if (totalDocs == 0) return this

        // 分词 + 统计
        val allTokens = documents.map { tokenize(it) }
        val docLengths = allTokens.map { it.size }
        avgDocLength = docLengths.average()

        // 每个文档的词频
        termFreqs = allTokens.map { tokens ->
            tokens.groupBy { it }.mapValues { it.value.size }
        }

        // 文档频率（每个词出现在多少个文档中）
        val df = mutableMapOf<String, MutableSet<Int>>()
        allTokens.forEachIndexed { docIdx, tokens ->
            tokens.toSet().forEach { term ->
                df.getOrPut(term) { mutableSetOf() }.add(docIdx)
            }
        }
        docFreq = df.mapValues { it.value.size }

        built = true
        return this
    }

    /**
     * 对单个 query 计算所有文档的 BM25 分数。
     * @return 按分数降序的 (文档索引, 分数) 列表
     */
    fun search(query: String): List<Pair<Int, Float>> {
        if (!built || totalDocs == 0) return emptyList()

        val queryTokens = tokenize(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()

        val scores = FloatArray(totalDocs)

        for (term in queryTokens) {
            val df = docFreq[term] ?: continue // 词不在任何文档中

            // IDF: ln(1 + (N - df + 0.5) / (df + 0.5))
            val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))

            for (docIdx in 0 until totalDocs) {
                val tf = termFreqs[docIdx][term] ?: 0
                if (tf == 0) {
                    // BM25+: 给零频词一个极小的基础分
                    scores[docIdx] += (delta * idf).toFloat()
                    continue
                }
                val docLen = termFreqs[docIdx].values.sum()
                // BM25 scoring formula
                val numerator = tf.toFloat() * (k1 + 1)
                val denominator = tf.toFloat() + k1 * (1 - b + b * (docLen.toDouble() / avgDocLength))
                scores[docIdx] += (idf * numerator / denominator).toFloat()
            }
        }

        return scores.mapIndexed { idx, score -> idx to score }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
    }

    /**
     * 中文 + 英文混合分词。
     * 英文按空格/标点分词，中文按单字 + 二元组。
     */
    private fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val tokens = mutableListOf<String>()
        val cleaned = text.lowercase().trim()

        // 提取英文词（含数字和下划线）
        val englishPattern = Regex("[a-z0-9_]+")
        englishPattern.findAll(cleaned).forEach { match ->
            tokens.add(match.value)
        }

        // 提取中文字符（连续中文按二元组切）
        val chinesePattern = Regex("[\\u4e00-\\u9fff]+")
        chinesePattern.findAll(cleaned).forEach { match ->
            val chars = match.value
            // 单字
            chars.forEach { c -> tokens.add(c.toString()) }
            // 二元组
            if (chars.length >= 2) {
                for (i in 0 until chars.length - 1) {
                    tokens.add(chars.substring(i, i + 2))
                }
            }
            // 三元组（重要概念如"机器学习"、"自然语言"）
            if (chars.length >= 3) {
                for (i in 0 until chars.length - 2) {
                    tokens.add(chars.substring(i, i + 3))
                }
            }
        }

        return tokens.distinct() // 同一文档内去重
    }

    /** 索引是否已构建 */
    fun isBuilt(): Boolean = built
}
