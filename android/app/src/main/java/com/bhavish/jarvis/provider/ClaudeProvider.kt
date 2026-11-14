package com.bhavish.jarvis.provider

import org.json.JSONObject

class ClaudeProvider : AiProvider {
    override val id = ProviderId.CLAUDE
    override suspend fun complete(apiKey: String, model: String, messages: List<AiMessage>): AiResult {
        val json = ProviderHttp.post("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            ProviderPayloads.claude(model, messages))
        val blocks = json.optJSONArray("content")
        val text = buildString {
            if (blocks != null) for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i)
                if (block?.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) throw ProviderException("Claude returned no text response")
        val usage = json.optJSONObject("usage") ?: JSONObject()
        return AiResult(text, TokenUsage(usage.optInt("input_tokens").takeIf { it > 0 },
            usage.optInt("output_tokens").takeIf { it > 0 }))
    }
}
