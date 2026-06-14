package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.ai.ui.UIMessagePart

private fun errEnv(error: String, detail: String) = listOf(
    UIMessagePart.Text(buildJsonObject { put("error", error); put("detail", detail) }.toString())
)

private fun encodeRun(run: SubAgentRun) = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("started_at_ms", run.startedAtMs)
    run.finishedAtMs?.let { put("finished_at_ms", it) }
    run.result?.let { put("result", it) }
    run.error?.let { put("error", it) }
}

/** 派发一个子代理 */
fun subagentDispatchTool(
    engine: SubAgentEngine,
    context: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_dispatch",
    description = """
        Dispatch a focused sub-agent — a clean-context LLM run that returns a concise summary.
        Use when the task is independent (research, lookup, multi-step work) and would pollute
        your context with intermediate output. Pass a clear, self-contained task. For long-running
        work, set run_in_background=true and poll with subagent_get.
        Concurrency limits: max 4 per assistant, 16 globally.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject { put("type", "string"); put("description", "The task for the sub-agent to execute") })
                put("label", buildJsonObject { put("type", "string"); put("description", "A short label to identify this sub-agent") })
                put("run_in_background", buildJsonObject { put("type", "boolean"); put("description", "If true, return immediately and run in background") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Timeout in seconds (default 300)") })
            },
            required = listOf("task"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val task = params["task"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_task", "task is required")
        val request = SubAgentRequest(
            task = task,
            label = params["label"]?.jsonPrimitive?.contentOrNull,
            runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false,
            timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.longOrNull ?: 300L,
        )
        val parentAssistantId = context.callerAssistantId.orEmpty()
        val parentChatId = context.callerConversationId
        when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
            is SubAgentEngine.DispatchResult.Reject -> errEnv(res.error, res.detail)
            is SubAgentEngine.DispatchResult.Ok -> listOf(UIMessagePart.Text(encodeRun(res.run).toString()))
        }
    },
)

/** 列出子代理 */
fun subagentListTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_list",
    description = "List sub-agent runs. Set active_only=true to omit terminal runs. Read-only.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("active_only", buildJsonObject { put("type", "boolean") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val activeOnly = args.jsonObject["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val arr = buildJsonArray {
            registry.list(activeOnly).forEach {
                addJsonObject {
                    put("id", it.id)
                    put("label", it.label)
                    put("status", it.status.name)
                    put("started_at_ms", it.startedAtMs)
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("runs", arr) }.toString()))
    },
)

/** 查子代理详情 */
fun subagentGetTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val run = registry.get(id) ?: return@Tool errEnv("unknown_id", "no sub-agent with id $id")
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

/** 取消子代理 */
fun subagentCancelTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel",
    description = "Cancel a running sub-agent by id. Safe to call on already-terminal runs.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val cancelled = registry.requestCancel(id)
        if (cancelled) {
            registry.update(id) { it.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("ok", cancelled); put("id", id) }.toString()))
    },
)
