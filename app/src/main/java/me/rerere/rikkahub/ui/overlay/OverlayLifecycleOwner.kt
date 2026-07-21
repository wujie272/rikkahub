package me.rerere.rikkahub.ui.overlay

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 为 WindowManager 悬浮窗提供 Compose 所需的 LifecycleOwner / ViewModelStoreOwner /
 * SavedStateRegistryOwner。
 *
 * 用法：
 *   val owner = OverlayLifecycleOwner()
 *   owner.attach(composeView)
 *   wm.addView(composeView, params)
 *   owner.start()
 *   // ...
 *   owner.destroy()
 *   wm.removeView(composeView)
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStore
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /**
     * 绑定到 View 树：在 addView 之前调用。
     * 调用后 ComposeView 就能通过 ViewTree 找到这三个 Owner。
     */
    fun attach(view: View) {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    /**
     * 触发 ON_START 和 ON_RESUME，Compose 开始重组。
     * 在 addView 之后调用（View 已 attach 到 Window）。
     */
    fun start() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * 触发 ON_PAUSE 和 ON_STOP，Compose 暂停后台工作。
     * 在 removeView 之前调用。
     */
    fun pause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    /**
     * 触发 ON_DESTROY 并清理 ViewModelStore。
     * 在 removeView 之后调用。
     */
    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
