package me.rerere.rikkahub.data.ai

enum class AIRequestSource {
    CHAT,
    TITLE_SUMMARY,
    CONTEXT_SUMMARY,
    CHAT_SUGGESTION,
    WELCOME_PHRASES,
    GROUP_CHAT_ROUTING,
    MEMORY_CONSOLIDATION,
    MEMORY_EMBEDDING,
    MEMORY_RETRIEVAL,
    TOOL_RESULT_EMBEDDING,
    TOOL_RESULT_RAG,
    TRANSLATION,
    OCR,
    DOCUMENT_SUMMARY,
    SCHEDULED_MESSAGE,
    SPONTANEOUS,
    MODEL_NAME_GENERATION,
    SEARCH_AGENT,
    SPEECH_TO_TEXT,
    OTHER,
}

fun AIRequestSource.displayName(): String = when (this) {
    AIRequestSource.CHAT -> "Chat"
    AIRequestSource.TITLE_SUMMARY -> "Title Summary"
    AIRequestSource.CONTEXT_SUMMARY -> "Context Summary"
    AIRequestSource.CHAT_SUGGESTION -> "Chat Suggestion"
    AIRequestSource.WELCOME_PHRASES -> "Welcome Phrases"
    AIRequestSource.GROUP_CHAT_ROUTING -> "Group Chat Routing"
    AIRequestSource.MEMORY_CONSOLIDATION -> "Memory Consolidation"
    AIRequestSource.MEMORY_EMBEDDING -> "Memory Embedding"
    AIRequestSource.MEMORY_RETRIEVAL -> "Memory Retrieval"
    AIRequestSource.TOOL_RESULT_EMBEDDING -> "Tool Result Embedding"
    AIRequestSource.TOOL_RESULT_RAG -> "Tool Result RAG"
    AIRequestSource.TRANSLATION -> "Translation"
    AIRequestSource.OCR -> "OCR"
    AIRequestSource.DOCUMENT_SUMMARY -> "Document Summary"
    AIRequestSource.SCHEDULED_MESSAGE -> "Scheduled Message"
    AIRequestSource.SPONTANEOUS -> "Spontaneous"
    AIRequestSource.MODEL_NAME_GENERATION -> "Model Name Generation"
    AIRequestSource.SEARCH_AGENT -> "Search Agent"
    AIRequestSource.SPEECH_TO_TEXT -> "Speech to Text"
    AIRequestSource.OTHER -> "Other"
}
