package me.rerere.rikkahub.data.ai.tools.local

import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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

/** 把 console.* 收敛到一个绑定的 Kotlin 函数，便于把日志带回给模型。 */
private const val CONSOLE_POLYFILL = """
globalThis.console = {
    log: (...a) => __log('LOG', ...a),
    info: (...a) => __log('INFO', ...a),
    warn: (...a) => __log('WARN', ...a),
    error: (...a) => __log('ERROR', ...a),
    debug: (...a) => __log('DEBUG', ...a),
};
void 0;
"""

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
        val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val logs = mutableListOf<String>()

        val payload = withTimeoutOrNull(EVAL_JS_TIMEOUT_MS) {
            runCatching {
                // 迁移自旧 wang.harlon.quickjs wrapper（上游已统一到 quickjs-kt）。
                val resultJson: String? = quickJs(Dispatchers.Default) {
                    function("__log") { args ->
                        val level = args.getOrNull(0) as? String ?: "LOG"
                        val message = args.drop(1).joinToString(" ") { arg -> arg?.toString() ?: "null" }
                        logs += "[$level] $message"
                        null
                    }
                    evaluate<Unit>(CONSOLE_POLYFILL)
                    // 用 (0, eval) 间接调用取得最后表达式的值；undefined -> JSON.stringify 返回 null
                    evaluate("JSON.stringify((0, eval)(${Json.encodeToString(JsonPrimitive(code))}))")
                }
                buildJsonObject {
                    if (logs.isNotEmpty()) put("logs", JsonPrimitive(logs.joinToString("\n")))
                    put("result", resultJson?.let { JsonPrimitive(it) } ?: JsonNull)
                }.toString()
            }.getOrElse { e ->
                buildJsonObject {
                    if (logs.isNotEmpty()) put("logs", JsonPrimitive(logs.joinToString("\n")))
                    put("error", JsonPrimitive(e.message ?: e.toString()))
                }.toString()
            }
        } ?: buildJsonObject {
            if (logs.isNotEmpty()) put("logs", JsonPrimitive(logs.joinToString("\n")))
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
