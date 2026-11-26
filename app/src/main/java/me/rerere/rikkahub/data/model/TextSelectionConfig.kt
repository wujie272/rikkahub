package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class TextSelectionConfig(
    val assistantId: Uuid? = null,
    val actions: List<TextSelectionAction> = DEFAULT_TEXT_SELECTION_ACTIONS,
    val translateLanguage: String = "English"
)

@Serializable
data class TextSelectionAction(
    val id: String = Uuid.random().toString(),
    val name: String,
    val icon: String,
    val prompt: String,
    val enabled: Boolean = true,
    val isCustomPrompt: Boolean = false,
    val modelId: Uuid? = null,
)

val DEFAULT_TEXT_SELECTION_ACTIONS = listOf(
    TextSelectionAction(
        id = "translate",
        name = "Translate",
        icon = "Translate",
        prompt = "You are a translator. Translate the user's text to {{language}}.\nOnly output the translation, nothing else. Do not include any explanations or notes.",
    ),
    TextSelectionAction(
        id = "explain",
        name = "Explain",
        icon = "Lightbulb",
        prompt = "You are a helpful assistant. Explain the following text in simple, easy-to-understand terms.\nBe concise but thorough. Use examples if helpful.",
    ),
    TextSelectionAction(
        id = "summarize",
        name = "Summarize",
        icon = "Summarize",
        prompt = "You are a summarization assistant. Provide a clear, concise summary of the following text.\nCapture the key points and main ideas. Be brief but complete.",
    ),
    TextSelectionAction(
        id = "custom",
        name = "Ask anything\u2026",
        icon = "AutoAwesome",
        prompt = "You are a helpful assistant. Answer the user's question about the provided text.\nUser's question: {{custom_prompt}}",
        isCustomPrompt = true,
    ),
)
