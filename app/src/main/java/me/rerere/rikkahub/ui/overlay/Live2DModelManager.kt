package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Live2D 模型管理器。
 * 负责扫描、导入、选择模型文件。
 */
object Live2DModelManager {
    private const val TAG = "Live2DModelManager"
    private const val MODELS_DIR = "live2d/models"

    /**
     * 模型信息
     */
    data class ModelInfo(
        val name: String,
        val modelFile: File,
        val dir: File
    )

    /**
     * 获取模型存储目录
     */
    fun getModelsDir(context: Context): File {
        return File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }

    /**
     * 扫描已导入的模型列表
     */
    fun scanModels(context: Context): List<ModelInfo> {
        val dir = getModelsDir(context)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { modelDir ->
            val modelFile = findModelFile(modelDir)
            if (modelFile != null) {
                ModelInfo(
                    name = modelDir.name,
                    modelFile = modelFile,
                    dir = modelDir
                )
            } else null
        } ?: emptyList()
    }

    /**
     * 在目录中查找 model3.json 文件
     */
    private fun findModelFile(dir: File): File? {
        // 优先找 model3.json
        val model3 = File(dir, "model3.json")
        if (model3.exists()) return model3

        // 递归查找
        return dir.walkTopDown().firstOrNull {
            it.name.endsWith(".model3.json")
        }
    }

    /**
     * 验证模型目录是否包含运行所需的文件
     */
    fun validateModel(modelDir: File): String? {
        val modelFile = findModelFile(modelDir) ?: return "未找到 model3.json"
        val modelJson = try {
            modelFile.readText()
        } catch (e: Exception) {
            return "无法读取 model3.json: ${e.message}"
        }
        // 检查是否有 .moc3 引用（浅校验）
        if (!modelJson.contains(".moc3")) {
            return "model3.json 中未引用 .moc3 文件"
        }
        // 检查是否有贴图引用
        val hasTextures = modelJson.contains("textures") ||
                modelDir.walkTopDown().any { it.extension == "png" }
        if (!hasTextures) {
            return "模型缺少贴图文件"
        }
        return null // 校验通过
    }

    /**
     * 导入模型文件（从 SAF 返回的 URI）
     * 只处理 .zip 导入，应在后台线程调用
     *
     * @return 成功返回目标目录名，失败返回 null
     */
    @WorkerThread
    fun importModel(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val modelsDir = getModelsDir(context)

            // 用时间戳作为目录名，不依赖 fileName
            val targetDirName = "model_${System.currentTimeMillis()}"
            val targetDir = File(modelsDir, targetDirName)
            targetDir.mkdirs()

            val zipStream = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                val safeName = sanitizeEntryName(entry.name)
                val targetFile = File(targetDir, safeName)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { output ->
                        zipStream.copyTo(output)
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()
            inputStream.close()

            // 导入后验证
            val validationError = validateModel(targetDir)
            if (validationError != null) {
                // 验证失败，清理导入的目录
                targetDir.deleteRecursively()
                Log.w(TAG, "Model validation failed: $validationError")
                return null
            }

            // 用模型名重命名目录（从 model3.json 里读）
            val modelFile = findModelFile(targetDir) ?: return targetDirName
            val displayName = try {
                val json = modelFile.readText()
                // 从 JSON 中提取 "Version" 前面的字段，或者就用目录名
                val nameMatch = Regex("\"Name\"\\s*:\\s*\"([^\"]+)\"").find(json)
                nameMatch?.groupValues?.getOrNull(1)
            } catch (_: Exception) { null }

            if (displayName != null && displayName.isNotBlank()) {
                val renamedDir = File(modelsDir, displayName)
                if (!renamedDir.exists()) {
                    targetDir.renameTo(renamedDir)
                }
            }

            Log.i(TAG, "Model imported: $targetDirName")
            targetDirName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model", e)
            null
        }
    }

    /**
     * 防 ZIP Slip：规范化 entry 路径，拒绝 .. 穿越
     */
    private fun sanitizeEntryName(entryName: String): String {
        // 替换反斜杠为正斜杠（Windows 创建的 ZIP）
        val normalized = entryName.replace('\\', '/')
        // 按 / 分段，过滤掉 . 和 .. 段
        val parts = normalized.split('/').filter { part ->
            part.isNotEmpty() && part != "." && part != ".."
        }
        return parts.joinToString("/")
    }

    /**
     * 删除模型
     */
    fun deleteModel(model: ModelInfo): Boolean {
        return try {
            model.dir.deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${model.name}", e)
            false
        }
    }
}
