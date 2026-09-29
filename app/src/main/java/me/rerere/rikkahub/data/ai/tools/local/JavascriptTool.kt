package me.rerere.rikkahub.data.ai.tools.local

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val EVAL_JS_TIMEOUT_MS = 5_000L

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull

        val done = CompletableDeferred<String>()
        Thread {
            val logs = arrayListOf<String>()
            var context: QuickJSContext? = null
            try {
                context = QuickJSContext.create()
                context.setMemoryLimit(64 * 1024 * 1024)
                context.setMaxStackSize(512 * 1024)
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) {
                        logs.add("[LOG] $info")
                    }

                    override fun info(info: String?) {
                        logs.add("[INFO] $info")
                    }

                    override fun warn(info: String?) {
                        logs.add("[WARN] $info")
                    }

                    override fun error(info: String?) {
                        logs.add("[ERROR] $info")
                    }
                })
                val result = context.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                done.complete(payload.toString())
            } catch (e: Throwable) {
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put("error", JsonPrimitive(e.message ?: e.toString()))
                }
                done.complete(payload.toString())
            } finally {
                try {
                    context?.destroy()
                } catch (_: Throwable) {
                }
            }
        }.apply {
            name = "eval-js-${System.nanoTime()}"
            isDaemon = true
            start()
        }

        val payload = withTimeoutOrNull(EVAL_JS_TIMEOUT_MS) { done.await() }
            ?: buildJsonObject {
                put(
                    "error",
                    JsonPrimitive(
                        "JavaScript execution exceeded ${EVAL_JS_TIMEOUT_MS}ms and was abandoned. " +
                            "Avoid infinite loops or long-running computations."
                    )
                )
            }.toString()
        listOf(UIMessagePart.Text(payload))
    }
)
