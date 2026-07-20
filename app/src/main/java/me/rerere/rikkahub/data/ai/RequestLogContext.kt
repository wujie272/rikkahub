package me.rerere.rikkahub.data.ai

/**
 * 用于将 AI 层的来源分类信息传递给 OkHttp 拦截器。
 * 使用 ThreadLocal 避免并发请求间的冲突。
 */
object RequestLogContext {
    private val threadLocal = object : ThreadLocal<Context>() {
        override fun initialValue(): Context = Context()
    }

    data class Context(
        val source: AIRequestSource = AIRequestSource.OTHER,
        val providerName: String = "",
        val modelId: String = "",
        val modelDisplayName: String = "",
    )

    fun current(): Context = threadLocal.get()

    fun set(ctx: Context) {
        threadLocal.set(ctx)
    }

    fun clear() {
        threadLocal.remove()
    }

    /**
     * 在指定代码块中设置上下文，完成后自动清除。
     */
    fun <T> withContext(ctx: Context, block: () -> T): T {
        val old = threadLocal.get()
        threadLocal.set(ctx)
        try {
            return block()
        } finally {
            threadLocal.set(old)
        }
    }
}
