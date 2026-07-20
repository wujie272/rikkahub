package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

private const val MAX_PREVIEW_CHARS = 240
private const val MAX_JSON_CHARS = 120_000
private const val MASKED_VALUE = "********"

private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "x-api-key",
    "api-key",
    "apikey",
    "api_key",
    "x-auth-token",
    "cookie",
    "set-cookie",
    "token",
    "access_token",
    "refresh_token",
    "secret",
    "password",
    "private_key",
    "client_secret",
    "x-secret",
)

/**
 * 脱敏请求头中敏感字段的值
 */
fun Map<String, String>.maskSensitiveHeaders(): Map<String, String> {
    return this.mapValues { (key, value) ->
        if (key.trim().lowercase() in SENSITIVE_HEADERS) MASKED_VALUE else value
    }
}

/**
 * 脱敏 JSON 字符串中的敏感字段值（递归遍历）
 */
fun String.maskSensitiveJson(): String {
    val trimmed = trim()
    if (trimmed.isBlank() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
        return this
    }
    return runCatching {
        val element = JsonInstant.parseToJsonElement(trimmed)
        val masked = element.maskSensitiveValues()
        JsonInstant.encodeToString(JsonElement.serializer(), masked)
    }.getOrElse { this }
}

/**
 * 截断到预览长度（240字符），过长时加后缀
 */
fun String.truncatePreview(): String {
    if (length <= MAX_PREVIEW_CHARS) return this
    return take(MAX_PREVIEW_CHARS) + "\n... (truncated)"
}

/**
 * 截断到 JSON 最大长度（120K字符），过长时加后缀
 */
fun String.truncateJson(): String {
    if (length <= MAX_JSON_CHARS) return this
    return take(MAX_JSON_CHARS) + "\n... (truncated)"
}

/**
 * 递归遍历 JSON，脱敏敏感 key 的值
 */
private fun JsonElement.maskSensitiveValues(): JsonElement {
    return when (this) {
        is JsonObject -> {
            JsonObject(
                this.entries.associate { (key, value) ->
                    if (key.trim().lowercase() in SENSITIVE_HEADERS) {
                        key to JsonPrimitive(MASKED_VALUE)
                    } else {
                        key to value.maskSensitiveValues()
                    }
                }
            )
        }
        is JsonArray -> JsonArray(this.map { it.maskSensitiveValues() })
        else -> this
    }
}

/**
 * 对请求体做完整处理：脱敏 + 截断预览
 */
fun String.sanitizeRequestBody(): String {
    return this.maskSensitiveJson().truncatePreview()
}

/**
 * 对响应原始文本做完整处理：脱敏 + 截断
 */
fun String.sanitizeResponseRaw(): String {
    return this.maskSensitiveJson().truncateJson()
}
