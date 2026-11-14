package com.bhavish.jarvis.provider

class OpenRouterProvider : AiProvider {
    override val id = ProviderId.OPENROUTER
    override suspend fun complete(apiKey: String, model: String, messages: List<AiMessage>) =
        openAiCompatible("https://openrouter.ai/api/v1/chat/completions", apiKey, model, messages,
            mapOf("X-Title" to "JARVIS Android"))
}
