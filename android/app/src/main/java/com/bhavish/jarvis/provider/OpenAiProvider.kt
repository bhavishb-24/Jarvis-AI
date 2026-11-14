package com.bhavish.jarvis.provider

import org.json.JSONObject

internal suspend fun openAiCompatible(url: String, key: String, model: String,
                                      messages: List<AiMessage>, extraHeaders: Map<String, String> = emptyMap()): AiResult {
    val json = ProviderHttp.post(url, mapOf("Authorization" to "Bearer $key") + extraHeaders,
        ProviderPayloads.openAi(model, messages))
    val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        ?.optString("content").orEmpty().trim()
    if (text.isEmpty()) throw ProviderException("Provider returned no text response")
    val usage = json.optJSONObject("usage") ?: JSONObject()
    return AiResult(text, TokenUsage(usage.optInt("prompt_tokens").takeIf { it > 0 },
        usage.optInt("completion_tokens").takeIf { it > 0 }))
}

class OpenAiProvider : AiProvider {
    override val id = ProviderId.OPENAI
    override suspend fun complete(apiKey: String, model: String, messages: List<AiMessage>) =
        openAiCompatible("https://api.openai.com/v1/chat/completions", apiKey, model, messages)
}
