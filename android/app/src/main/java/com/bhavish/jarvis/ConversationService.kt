package com.bhavish.jarvis

import com.bhavish.jarvis.data.ConversationDao
import com.bhavish.jarvis.data.ConversationEntity
import com.bhavish.jarvis.data.KeyVault
import com.bhavish.jarvis.data.MessageEntity
import com.bhavish.jarvis.data.SettingsStore
import com.bhavish.jarvis.provider.AiMessage
import com.bhavish.jarvis.provider.ProviderManager

class ConversationService(
    private val dao: ConversationDao,
    private val settings: SettingsStore,
    private val vault: KeyVault,
    private val providers: ProviderManager
) {
    suspend fun newConversation(): Long = dao.insertConversation(ConversationEntity(title = "New conversation"))

    suspend fun send(conversationId: Long, text: String): Result<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Message is empty"))
        val route = settings.route()
        dao.insertMessage(MessageEntity(conversationId = conversationId, role = "user", text = clean))
        val context = dao.recentMessages(conversationId, 12).asReversed()
            .filter { it.role == "user" || it.role == "assistant" }
            .map { AiMessage(it.role, it.text) }
        if (context.size == 1) dao.updateTitle(conversationId, clean.take(28))
        return runCatching {
            val result = providers.complete(route, vault.read(route.provider), context)
            dao.insertMessage(MessageEntity(conversationId = conversationId, role = "assistant",
                text = result.text, provider = route.provider.name, model = route.model,
                inputTokens = result.usage.input, outputTokens = result.usage.output))
            result.text
        }
    }
}
