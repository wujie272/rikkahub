package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.ai.mcp.McpManager
import kotlin.uuid.Uuid

/**
 * MCP 服务器名不合法（含非 ASCII 字母/数字字符）时抛出。
 * 由调用方捕获后暂停消息队列并报错（上游 097cdb904 语义）。
 */
class InvalidMcpServerNamesException(val names: List<String>) :
    Exception("Invalid MCP server names: ${names.joinToString(", ")}")

/**
 * 对话工具装配工厂（移植上游 097cdb904 的职责划分）。
 *
 * 本地版本把原先内联在 ChatService 里的装配逻辑搬到这里，仍使用本地自己的
 * 工具集合（本地工具 / 知识库 / 技能 / 工作区 / MCP 命名空间化）。
 */
class ChatToolFactory(
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val workspaceRepository: WorkspaceRepository,
    private val knowledgeService: me.rerere.rikkahub.data.knowledge.KnowledgeService,
    private val conversationRepo: ConversationRepository,
    private val skillManager: SkillManager,
) {
    private suspend fun createChatTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
    ): List<Tool> = buildList {
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    // Pass the caller context so context-aware tools (subagent_dispatch
                    // recursion guard, workflow_create authoring-id) can read the
                    // calling conversation + assistant. isHeadless is read from
                    // HeadlessConversations — true iff this is a cron / sub-agent /
                    // workflow / external-automation flow.
                    val invocationCtx = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                        callerAssistantId = assistant.id.toString(),
                        callerConversationId = conversationId.toString(),
                        isHeadless = me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .isHeadless(conversationId),
                        // show_image keys its result envelope off this — a text-only model
                        // gets told it cannot see the image instead of confabulating one.
                        modelCanSeeImages = Modality.IMAGE in model.inputModalities,
                    )
                    addAll(localTools.getTools(assistant.localTools, invocationCtx))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledKnowledgeBaseIds.isNotEmpty()) {
                        addAll(createKnowledgeBaseTools(knowledgeService))
                    }
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        // Upstream name validation: a server name that isn't pure
                        // English+digits would produce an invalid `mcp__<name>__tool`
                        // surface, so surface it as an error rather than emit a tool the
                        // model can't address.
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            // 报错与暂停队列由调用方（ChatService）负责
                            throw InvalidMcpServerNamesException(invalidNames)
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        // Namespace MCP tools by a server-id slug so two enabled servers that
                        // each expose a tool of the same name don't collide (which would 400 or
                        // mis-route to whichever server registered last). Keep the `mcp__` prefix
                        // intact: HardlineCommandGuard and ToolApprovalDefaults both branch on
                        // `startsWith("mcp__")`. The slug is the first 8 hex chars of the id with
                        // dashes stripped; the validated server name follows for human-readable
                        // disambiguation, keeping the name within the 64-char /
                        // ^[a-zA-Z0-9_-]+$ limit. The execute lambda below still calls callTool
                        // with the REAL tool.name, since the namespacing exists only on the
                        // model-facing surface.
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                // MCP tools default to NO approval — the per-tool `needsApproval`
                                // flag (settable in Settings → MCP → Tools tab, defaults to false)
                                // is the single source of truth. The user can flip individual MCP
                                // tools to require approval when they're known to be destructive.
                                // HARDLINE still applies via HardlineCommandGuard's `mcp__*` branch,
                                // which scans every string arg for shell-content patterns
                                // (rm -rf /, mkfs, shutdown, encoded payloads).
                                needsApproval = {
                                    me.rerere.rikkahub.data.ai.tools
                                        .ToolApprovalDefaults.requiresApproval(mcpToolName) ||
                                        tool.needsApproval
                                },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
