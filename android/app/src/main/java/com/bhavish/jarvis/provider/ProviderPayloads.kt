package com.bhavish.jarvis.provider

import org.json.JSONArray
import org.json.JSONObject

object ProviderPayloads {
    fun openAi(model: String, messages: List<AiMessage>): JSONObject = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().also { out ->
            messages.forEach { out.put(JSONObject().put("role", it.role).put("content", it.text)) }
        })

    fun gemini(messages: List<AiMessage>): JSONObject = JSONObject()
        .put("contents", JSONArray().also { out ->
            messages.forEach {
                out.put(JSONObject()
                    .put("role", if (it.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
            }
        })

    fun claude(model: String, messages: List<AiMessage>): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", 1024)
        .put("messages", JSONArray().also { out ->
            messages.forEach { out.put(JSONObject().put("role", it.role).put("content", it.text)) }
        })
}
