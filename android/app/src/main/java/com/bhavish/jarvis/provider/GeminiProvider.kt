package com.bhavish.jarvis.provider

import org.json.JSONObject
import java.net.URLEncoder

class GeminiProvider : AiProvider {
    override val id = ProviderId.GEMINI

    override suspend fun complete(apiKey: String, model: String, messages: List<AiMessage>): AiResult {
        val safeModel = URLEncoder.encode(model, "UTF-8")
        val json = ProviderHttp.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent",
            mapOf("x-goog-api-key" to apiKey), ProviderPayloads.gemini(messages)
        )
        val candidates = json.optJSONArray("candidates")
        val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }.trim()
        if (text.isEmpty()) throw ProviderException("Gemini returned no text response")
        val usage = json.optJSONObject("usageMetadata") ?: JSONObject()
        return AiResult(text, TokenUsage(usage.optInt("promptTokenCount").takeIf { it > 0 },
            usage.optInt("candidatesTokenCount").takeIf { it > 0 }))
    }
}
