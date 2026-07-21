package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

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
     * 导入模型文件（从 SAF 返回的 URI）
     * 简化版：只处理 .zip 导入
     */
    fun importModel(context: Context, uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val fileName = uri.lastPathSegment ?: "model.zip"
            val modelsDir = getModelsDir(context)
            val targetDir = File(modelsDir, fileName.removeSuffix(".zip"))
            targetDir.mkdirs()

            // 解压到目标目录
            val zipStream = java.util.zip.ZipInputStream(inputStream)
            var entry = zipStream.nextEntry
            while (entry != null) {
                val targetFile = File(targetDir, entry.name)
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

            Log.i(TAG, "Model imported: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model", e)
            false
        }
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
