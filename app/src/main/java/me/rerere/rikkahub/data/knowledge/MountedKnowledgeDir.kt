package me.rerere.rikkahub.data.knowledge

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 挂载的外部目录 —— 只存元数据，不存文件内容。
 *
 * 搜索时直接走 [FileSystemSearchEngine] 在原文件上搜，
 * 零额外存储，只有目录 URI 和路径解析结果。
 */
@Entity(
    tableName = "mounted_knowledge_dir",
    indices = [
        Index("kb_id"),
    ]
)
data class MountedKnowledgeDir(
    @PrimaryKey
    val id: String,
    /** 关联的知识库 ID */
    @androidx.room.ColumnInfo(name = "kb_id")
    val kbId: String,
    /** 用户命名的显示名 */
    val name: String,
    /** SAF tree URI（持久化用） */
    @androidx.room.ColumnInfo(name = "tree_uri")
    val treeUri: String,
    /**
     * 解析后的 POSIX 路径，如 /storage/emulated/0/Documents/财报/
     * 由 SAF URI 解析得到，null 表示路径无法解析（云盘、已卸载等）
     */
    @androidx.room.ColumnInfo(name = "posix_path")
    val posixPath: String?,
    /** 文件数量（上次同步时统计） */
    @androidx.room.ColumnInfo(name = "file_count")
    val fileCount: Int = 0,
    /** 总大小（字节） */
    @androidx.room.ColumnInfo(name = "total_size_bytes")
    val totalSizeBytes: Long = 0,
    /** 上次同步时间戳 */
    @androidx.room.ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long = 0,
    /** 是否自动同步 */
    @androidx.room.ColumnInfo(name = "auto_sync")
    val autoSync: Boolean = true,
    /** 创建时间 */
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
