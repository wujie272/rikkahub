package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import com.arkueid.alive2d.Live2D
import com.arkueid.alive2d.Live2DModel
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D 渲染器。
 * 管理 GLSurfaceView 和 JNI 桥接，为悬浮球提供 Live2D 角色渲染。
 *
 * 用法：
 *   val renderer = Live2DRenderer(context)
 *   renderer.loadModel(modelPath)
 *   glSurfaceView = renderer.surfaceView  // 添加到悬浮球
 */
class Live2DRenderer(private val context: Context) {
    companion object {
        private const val TAG = "Live2DRenderer"
    }

    /** GLSurfaceView 实例，可添加到悬浮球中 */
    val surfaceView: GLSurfaceView

    /** 当前加载的模型 */
    private var currentModel: Live2DModel? = null

    /** 模型路径 */
    private var modelPath: String? = null

    /** 是否已初始化 */
    private var initialized = false

    /** 上次更新时间戳，用于计算 deltaTime */
    private var lastUpdateTime = 0L

    init {
        surfaceView = object : GLSurfaceView(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return onTouch(event)
            }
        }.apply {
            setEGLContextClientVersion(2)
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    if (!initialized) {
                        Live2D.init()
                        initialized = true
                        lastUpdateTime = System.nanoTime()
                    }
                    modelPath?.let { loadModelInternal(it) }
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    currentModel?.resize(width, height)
                }

                override fun onDrawFrame(gl: GL10?) {
                    val now = System.nanoTime()
                    val deltaTime = if (lastUpdateTime > 0) {
                        (now - lastUpdateTime) / 1_000_000_000f
                    } else 0f
                    lastUpdateTime = now

                    Live2D.clearBuffer(0f, 0f, 0f, 0f)
                    currentModel?.let { model ->
                        model.update(deltaTime)
                        model.updateDrag(deltaTime)
                        model.draw()
                    }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    /**
     * 加载模型
     * @param path model3.json 文件路径
     */
    fun loadModel(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "Model file not found: $path")
            return
        }
        modelPath = path
        if (initialized) {
            surfaceView.queueEvent { loadModelInternal(path) }
        }
    }

    /**
     * 清除当前模型
     */
    fun clearModel() {
        modelPath = null
        surfaceView.queueEvent {
            currentModel?.destroy()
            currentModel = null
        }
    }

    /**
     * 暂停渲染（悬浮球隐藏时调用）
     */
    fun onPause() {
        surfaceView.onPause()
    }

    /**
     * 恢复渲染（悬浮球显示时调用）
     */
    fun onResume() {
        surfaceView.onResume()
    }

    /**
     * 释放资源
     */
    fun release() {
        clearModel()
        Live2D.dispose()
        initialized = false
    }

    // ==================== 内部实现 ====================

    private fun loadModelInternal(path: String) {
        try {
            currentModel?.destroy()
            val model = Live2DModel()
            val jsonContent = File(path).readText()
            model.loadModelJson(jsonContent)
            currentModel = model
            Log.i(TAG, "Model loaded: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $path", e)
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val model = currentModel ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                model.drag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return false
    }
}
