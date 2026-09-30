package me.rerere.rikkahub.data.ai.tools

/**
 * MCP 服务器名不合法（含非 ASCII 字母/数字字符）时抛出。
 *
 * 移植自上游 097cdb904：原先本地是在 buildList 内直接 `return`（静默中断生成），
 * 现在改为抛异常，由调用方捕获后暂停消息队列并报错，行为更明确。
 *
 * 注：上游同名文件里还有 ChatToolFactory（把工具装配抽成工厂）；本地的装配逻辑
 * 仍在 ChatService.createChatTools 中，待后续单独重构时再迁入。
 */
class InvalidMcpServerNamesException(val names: List<String>) :
    Exception("Invalid MCP server names: ${names.joinToString(", ")}")
