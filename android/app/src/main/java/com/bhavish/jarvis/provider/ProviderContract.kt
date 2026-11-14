package com.bhavish.jarvis.provider

enum class ProviderId(val label: String, val defaultModel: String, val supportsLiveVoice: Boolean = false) {
    GEMINI("Gemini", "gemini-2.5-flash"),
    OPENAI("OpenAI", "gpt-4.1-mini"),
    CLAUDE("Claude", "claude-sonnet-4-5"),
    OPENROUTER("OpenRouter", "openai/gpt-4.1-mini")
}

enum class TaskKind { CHAT, PLANNING, SUMMARY }

data class ModelRoute(val provider: ProviderId, val model: String)

data class ModelRouting(val chat: ModelRoute, val overrides: Map<TaskKind, ModelRoute> = emptyMap()) {
    fun forTask(task: TaskKind): ModelRoute = overrides[task] ?: chat
}

data class AiMessage(val role: String, val text: String)

data class TokenUsage(val input: Int? = null, val output: Int? = null)

data class AiResult(val text: String, val usage: TokenUsage = TokenUsage())

interface AiProvider {
    val id: ProviderId
    suspend fun complete(apiKey: String, model: String, messages: List<AiMessage>): AiResult
}

class ProviderException(message: String) : Exception(message)
