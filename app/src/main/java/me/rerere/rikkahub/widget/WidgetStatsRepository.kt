package me.rerere.rikkahub.widget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DataStore 实例 — 与 SettingsStore 共享同一个文件 */
private val Context.widgetPrefs by preferencesDataStore(name = "settings")

/**
 * Widget 统计查询仓库
 *
 * 直接打开 Room 的 SQLite 数据库文件，避免依赖 DI。
 * 同时读取 DataStore 解析模型名和助手名。
 *
 * 优化：
 * - DB 连接单例 + readOnly，不复用创建
 * - 读取 DataStore 解析真实模型/助手名称
 * - 合并 SQL 查询减少次数
 */
class WidgetStatsRepository(context: Context) {

    private val appContext = context.applicationContext

    /** DB 连接单例 */
    private val dbHelper: SQLiteOpenHelper by lazy {
        object : SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {}
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            override fun onConfigure(db: SQLiteDatabase) {
                super.onConfigure(db)
            }
        }
    }

    /** 名称缓存 — 从 DataStore 解析一次，后续复用 */
    private var nameCache: NameCache? = null

    private data class NameCache(
        val providerNames: Map<String, String>,   // providerId → provider.name
        val modelNames: Map<String, String>,       // modelId → model.name
        val assistantNames: Map<String, String>,   // assistantId → assistant.name
    )

    data class WidgetStats(
        val totalConversations: Int = 0,
        val topModelName: String = "N/A",
        val topModelPercentage: Int = 0,
        val topAssistantName: String = "N/A",
        val topAssistantPercentage: Int = 0,
        val totalMessages: Long = 0,
        val lastUpdated: String = "",
    )

    /** 获取统计数据 */
    fun getStats(): WidgetStats {
        // 懒加载名称缓存
        if (nameCache == null) {
            nameCache = loadNameCache()
        }

        val db = try {
            dbHelper.readableDatabase
        } catch (e: Exception) {
            return WidgetStats(lastUpdated = formatTime())
        }

        return try {
            val totalConversations = queryInt(db, "SELECT COUNT(*) FROM conversationentity")
            val totalMessages = queryLong(
                db,
                "SELECT COUNT(*) FROM message_node mn, json_each(mn.messages) j " +
                    "WHERE json_extract(j.value, '$.role') = 'assistant'"
            )

            // 按 modelId 统计
            val modelCounts = queryModelCounts(db)
            val (topModelName, topModelPct) = resolveTopModel(modelCounts, totalMessages)

            // 按 assistant_id 统计
            val assistantCounts = queryAssistantCounts(db)
            val totalConv = assistantCounts.values.sum().coerceAtLeast(1)
            val topAssistantEntry = assistantCounts.maxByOrNull { it.value }
            val (topAssistantName, topAssistantPct) = if (topAssistantEntry != null) {
                resolveAssistantName(topAssistantEntry.key) to (topAssistantEntry.value * 100 / totalConv)
            } else {
                "N/A" to 0
            }

            WidgetStats(
                totalConversations = totalConversations,
                topModelName = topModelName,
                topModelPercentage = topModelPct,
                topAssistantName = topAssistantName,
                topAssistantPercentage = topAssistantPct,
                totalMessages = totalMessages,
                lastUpdated = formatTime(),
            )
        } catch (e: Exception) {
            WidgetStats(lastUpdated = formatTime())
        }
        // 不 close — 单例复用
    }

    // ===== 名称解析 =====

    private fun resolveTopModel(
        modelCounts: Map<String, Int>,
        totalMessages: Long
    ): Pair<String, Int> {
        if (modelCounts.isEmpty() || totalMessages == 0L) return "N/A" to 0
        val top = modelCounts.maxByOrNull { it.value } ?: return "N/A" to 0
        val pct = (top.value * 100 / totalMessages).toInt()
        val name = resolveModelName(top.key)
        return name to pct
    }

    private fun resolveModelName(modelId: String): String {
        // 优先从缓存拿
        nameCache?.modelNames?.get(modelId)?.let { return it }

        // 已知前缀模型名
        val knownPrefixes = listOf("gpt-", "claude-", "gemini-", "o1-", "o3-", "deepseek-", "glm-", "qwen-")
        for (prefix in knownPrefixes) {
            if (modelId.startsWith(prefix)) {
                return modelId.take(24)
            }
        }
        // UUID 格式 → 取后 12 位更可读
        if (modelId.length == 36 && modelId.contains("-")) {
            return modelId.takeLast(12)
        }
        return modelId.take(20)
    }

    private fun resolveAssistantName(assistantId: String): String {
        // 优先从缓存拿
        nameCache?.assistantNames?.get(assistantId)?.let {
            if (it.isNotBlank()) return it
        }
        return "助手 ${assistantId.take(8)}"
    }

    /** 从 DataStore 读取 providers 和 assistants JSON，构建名称映射 */
    private fun loadNameCache(): NameCache {
        val providerMap = mutableMapOf<String, String>()
        val modelMap = mutableMapOf<String, String>()
        val assistantMap = mutableMapOf<String, String>()

        try {
            // 同步读取 DataStore — Widget 在 IO 线程跑，没问题
            val prefs = runBlocking {
                appContext.widgetPrefs.data.first()
            }

            // 解析 providers
            val providersJson = prefs[stringPreferencesKey("providers")] ?: "[]"
            try {
                val providers = JsonInstant.parseToJsonElement(providersJson).jsonArray
                providers.forEach { p ->
                    val obj = p.jsonObject
                    val pId = (obj["id"] as? JsonPrimitive)?.let { it.content.takeIf { it.isNotBlank() } } ?: return@forEach
                    val pName = (obj["name"] as? JsonPrimitive)?.let { it.content.takeIf { it.isNotBlank() } } ?: return@forEach
                    providerMap[pId] = pName

                    val models = obj["models"]?.let { it as? JsonArray } ?: return@forEach
                    models.forEach { m ->
                        val mObj = m.jsonObject
                        val mId = (mObj["id"] as? JsonPrimitive)?.let { it.content.takeIf { it.isNotBlank() } } ?: return@forEach
                        val mName = (mObj["name"] as? JsonPrimitive)?.let { it.content.takeIf { it.isNotBlank() } }
                            ?: (mObj["model"] as? JsonPrimitive)?.let { it.content.takeIf { it.isNotBlank() } }
                            ?: return@forEach
                        val shortName = mName
                            .removePrefix("gpt-")
                            .removePrefix("claude-")
                            .removePrefix("gemini-")
                            .removePrefix("deepseek-")
                        modelMap[mId] = shortName
                    }
                }
            } catch (_: Exception) {}

            // 解析 assistants
            val assistantsJson = prefs[stringPreferencesKey("assistants")] ?: "[]"
            try {
                val assistants = JsonInstant.parseToJsonElement(assistantsJson).jsonArray
                assistants.forEach { a ->
                    val obj = a.jsonObject
                    val aId = (obj["id"] as? JsonPrimitive)?.let { it.content.takeIf { it.isNotBlank() } } ?: return@forEach
                    val aName = (obj["name"] as? JsonPrimitive)?.let { it.content } ?: ""
                    assistantMap[aId] = aName
                }
            } catch (_: Exception) {}

        } catch (_: Exception) {
            // DataStore 不可用时静默降级
        }

        return NameCache(providerMap, modelMap, assistantMap)
    }

    // ===== SQL 查询 =====

    private fun queryInt(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long {
        db.rawQuery(sql, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun queryModelCounts(db: SQLiteDatabase): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        db.rawQuery(
            """SELECT json_extract(j.value, '$.modelId') AS model_id, COUNT(*) AS cnt
               FROM message_node mn, json_each(mn.messages) j
               WHERE json_extract(j.value, '$.role') = 'assistant'
                 AND json_extract(j.value, '$.modelId') IS NOT NULL
                 AND json_extract(j.value, '$.modelId') != ''
               GROUP BY model_id
               ORDER BY cnt DESC
               LIMIT 5""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val modelId = cursor.getString(0) ?: continue
                val count = cursor.getInt(1)
                if (modelId.isNotBlank()) counts[modelId] = count
            }
        }
        return counts
    }

    private fun queryAssistantCounts(db: SQLiteDatabase): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        db.rawQuery(
            """SELECT assistant_id, COUNT(*) AS cnt
               FROM conversationentity
               GROUP BY assistant_id
               ORDER BY cnt DESC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val count = cursor.getInt(1)
                counts[id] = count
            }
        }
        return counts
    }

    companion object {
        private const val DB_NAME = "rikka_hub"
        private const val DB_VERSION = 20

        fun formatTime(): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }
}
