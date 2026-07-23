package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import com.arkueid.alive2d.Live2D
import com.arkueid.alive2d.Live2DModel
import com.arkueid.alive2d.MotionPriority
import java.io.File
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D 桌面宠物渲染器。
 *
 * 相比基础版，增加了：
 * - 完整子系统的更新循环（呼吸、眨眼、物理、姿态、表情）
 * - 随机待机动作（空闲时自动播放）
 * - 触摸部位检测 + 对应反馈动作
 * - 视线跟随
 */
class Live2DRenderer(private val context: Context) {
    companion object {
        private const val TAG = "Live2DRenderer"
        private const val MIN_IDLE_INTERVAL = 3f
        private const val MAX_IDLE_INTERVAL = 8f
        private const val REACTION_COOLDOWN = 0.5f
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

    // ── 桌面宠物行为系统 ──

    /** 待机动作计时器 */
    private var idleMotionTimer = 0f
    /** 当前待机间隔（秒），每次随机 */
    private var idleMotionInterval = 5f
    /** 是否正在触摸 */
    private var isTouching = false
    /** 上次触摸位置 X */
    private var touchX = 0f
    /** 上次触摸位置 Y */
    private var touchY = 0f
    /** 反应动作冷却（防止连点刷动作） */
    private var reactionCooldown = 0f
    /** 随机数生成器 */
    private val rng = Random()

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

                    // 防止切后台回来 deltaTime 爆炸
                    val dt = deltaTime.coerceIn(0f, 0.1f)

                    Live2D.clearBuffer(0f, 0f, 0f, 0f)
                    currentModel?.let { model ->
                        // ── 完整子系统的更新（顺序很重要） ──
                        model.updateMotion(dt)
                        model.updateBreath(dt)
                        model.updateBlink(dt)
                        model.updateExpression(dt)
                        model.updatePhysics(dt)
                        model.updatePose(dt)
                        model.updateDrag(dt)
                        model.update(dt)  // 最终同步

                        // ── 待机动作系统 ──
                        reactionCooldown = (reactionCooldown - dt).coerceAtLeast(0f)
                        if (model.isMotionFinished() && !isTouching && reactionCooldown <= 0f) {
                            idleMotionTimer += dt
                            if (idleMotionTimer >= idleMotionInterval) {
                                // 随机选一个动作组：idle / tap 都可
                                val groups = arrayOf("idle", "tap")
                                val group = groups[rng.nextInt(groups.size)]
                                model.startRandomMotion(group, MotionPriority.IDLE)
                                idleMotionInterval = MIN_IDLE_INTERVAL +
                                        rng.nextFloat() * (MAX_IDLE_INTERVAL - MIN_IDLE_INTERVAL)
                                idleMotionTimer = 0f
                            }
                        }

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
            // 加载后立即播放一个待机动作
            model.startRandomMotion("idle", MotionPriority.IDLE)
            currentModel = model
            Log.i(TAG, "Model loaded: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $path", e)
        }
    }

    /**
     * 触摸事件处理
     * - 按下：检测触摸部位，播放对应反馈动作
     * - 滑动：视线跟随（通过 drag 参数）
     * - 抬起：重置状态
     */
    private fun onTouch(event: MotionEvent): Boolean {
        val model = currentModel ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                touchX = event.x
                touchY = event.y
                model.drag(event.x, event.y)
                // 排队到 GL 线程做 hitTest + 动作，避免线程冲突
                surfaceView.queueEvent {
                    playTapReaction(model, event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                model.drag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                idleMotionTimer = 0f
                surfaceView.queueEvent {
                    if (model.isMotionFinished()) {
                        model.startRandomMotion("tap", MotionPriority.NORMAL)
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * 根据触摸部位播放对应动作
     */
    private fun playTapReaction(model: Live2DModel, x: Float, y: Float) {
        reactionCooldown = REACTION_COOLDOWN
        try {
            val hitParts = model.hitPart(x, y, true)
            if (hitParts.isNotEmpty()) {
                val part = hitParts[0].lowercase()
                val motionGroup = when {
                    part.contains("head") || part.contains("face") ||
                            part.contains("ear") || part.contains("hair") -> "tap_head"
                    part.contains("body") || part.contains("torso") ||
                            part.contains("breast") || part.contains("hip") -> "tap_body"
                    part.contains("arm") || part.contains("hand") -> "tap_hand"
                    else -> "tap"
                }
                // 尝试特定动作组，不行就 fallback
                try {
                    model.startRandomMotion(motionGroup, MotionPriority.NORMAL)
                } catch (_: Exception) {
                    model.startRandomMotion("tap", MotionPriority.NORMAL)
                }
            } else {
                model.startRandomMotion("tap", MotionPriority.NORMAL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hit test failed, playing random tap", e)
            model.startRandomMotion("tap", MotionPriority.NORMAL)
        }
    }
}
