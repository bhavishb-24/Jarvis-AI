package com.bhavish.jarvis.provider

class ProviderManager {
    private val providers: Map<ProviderId, AiProvider> = listOf(
        GeminiProvider(), OpenAiProvider(), ClaudeProvider(), OpenRouterProvider()
    ).associateBy { it.id }

    suspend fun complete(route: ModelRoute, key: String, messages: List<AiMessage>): AiResult {
        if (key.isBlank()) throw ProviderException("Add a ${route.provider.label} API key in Providers")
        if (route.model.isBlank()) throw ProviderException("Choose a model for ${route.provider.label}")
        if (messages.isEmpty()) throw ProviderException("Message is empty")
        return providers.getValue(route.provider).complete(key, route.model.trim(), messages)
    }

    suspend fun test(route: ModelRoute, key: String): AiResult =
        complete(route, key, listOf(AiMessage("user", "Reply with OK.")))
}
