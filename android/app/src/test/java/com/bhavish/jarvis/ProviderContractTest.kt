package com.bhavish.jarvis

import com.bhavish.jarvis.provider.AiMessage
import com.bhavish.jarvis.provider.ModelRoute
import com.bhavish.jarvis.provider.ModelRouting
import com.bhavish.jarvis.provider.ProviderId
import com.bhavish.jarvis.provider.ProviderPayloads
import com.bhavish.jarvis.provider.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContractTest {
    @Test fun taskOverrideDoesNotChangeChatProvider() {
        val routing = ModelRouting(ModelRoute(ProviderId.GEMINI, "gemini-2.5-flash"),
            mapOf(TaskKind.PLANNING to ModelRoute(ProviderId.CLAUDE, "claude-sonnet-4-5")))
        assertEquals(ProviderId.GEMINI, routing.forTask(TaskKind.CHAT).provider)
        assertEquals("claude-sonnet-4-5", routing.forTask(TaskKind.PLANNING).model)
    }

    @Test fun providerCapabilitiesDoNotClaimLiveVoice() {
        ProviderId.entries.forEach { assertFalse(it.supportsLiveVoice) }
    }

    @Test fun geminiPayloadKeepsRoleAndText() {
        val body = ProviderPayloads.gemini(listOf(AiMessage("user", "hello"), AiMessage("assistant", "hi")))
        val turns = body.getJSONArray("contents")
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals("model", turns.getJSONObject(1).getString("role"))
        assertEquals("hi", turns.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun claudePayloadUsesMaxTokensAndMessages() {
        val body = ProviderPayloads.claude("claude-sonnet-4-5", listOf(AiMessage("user", "hello")))
        assertTrue(body.getInt("max_tokens") in 1..4096)
        assertEquals("hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun openAiPayloadContainsChosenModel() {
        val body = ProviderPayloads.openAi("gpt-4.1-mini", listOf(AiMessage("user", "hello")))
        assertEquals("gpt-4.1-mini", body.getString("model"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }
}
