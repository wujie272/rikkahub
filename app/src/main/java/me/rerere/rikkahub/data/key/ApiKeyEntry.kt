package me.rerere.rikkahub.data.key

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 独立管理的 API Key 实体
 *
 * 从 ProviderSetting 中剥离出来，每个 Key 独立存储、独立管理。
 * keyValue 字段不在 DataStore 中明文存储，而是通过 KeyManager 加密保存。
 */
@Serializable
data class ApiKeyEntry(
    val id: Uuid = Uuid.random(),
    val keyValue: String = "",
    val label: String = "",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    val usageCount: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastErrorMessage: String = "",
) {
    /** 用于 UI 显示的截断 Key */
    val maskedValue: String
        get() {
            val trimmed = keyValue.trim()
            if (trimmed.length <= 12) return trimmed
            return "${trimmed.take(8)}...${trimmed.takeLast(4)}"
        }

    /** Key 的前缀标识 */
    val prefix: String
        get() {
            val trimmed = keyValue.trim()
            val idx = trimmed.indexOf('-')
            return if (idx > 0) trimmed.substring(0, idx) else ""
        }
}
