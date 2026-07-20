package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // 脱敏请求头
        val requestHeaders = request.headers.toMap().maskSensitiveHeaders()
        // 脱敏 + 截断请求体
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8().sanitizeRequestBody()
        }

        val response: Response
        var error: String? = null
        var responseBodyText: String? = null

        try {
            response = chain.proceed(request)
            // 读取响应体
            val contentType = response.body?.contentType()
            responseBodyText = response.body?.string()
            // 用原内容创建新 body 放回去
            val newBody = responseBodyText?.toResponseBody(contentType ?: "application/json".toMediaTypeOrNull())
            val newResponse = response.newBuilder().body(newBody ?: response.body).build()

            val durationMs = System.currentTimeMillis() - startTime
            val responseHeaders = response.headers.toMap().maskSensitiveHeaders()
            val ctx = RequestLogContext.current()

            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    responseCode = newResponse.code,
                    responseHeaders = responseHeaders,
                    durationMs = durationMs,
                    error = error,
                    source = ctx.source.name,
                    providerName = ctx.providerName,
                    modelId = ctx.modelId,
                    modelDisplayName = ctx.modelDisplayName,
                    // 脱敏 + 截断响应体
                    responseRawText = responseBodyText?.sanitizeResponseRaw() ?: "",
                )
            )

            return newResponse
        } catch (e: Exception) {
            if (responseBodyText == null) {
                // 请求失败的情况
                error = e.message
                val durationMs = System.currentTimeMillis() - startTime
                val ctx = RequestLogContext.current()

                Logging.logRequest(
                    LogEntry.RequestLog(
                        tag = "HTTP",
                        url = request.url.toString(),
                        method = request.method,
                        requestHeaders = requestHeaders,
                        requestBody = requestBody,
                        durationMs = durationMs,
                        error = error,
                        source = ctx.source.name,
                        providerName = ctx.providerName,
                        modelId = ctx.modelId,
                        modelDisplayName = ctx.modelDisplayName,
                    )
                )
            }
            throw e
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }
}
