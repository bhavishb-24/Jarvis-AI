package com.bhavish.jarvis.data

import android.content.Context
import com.bhavish.jarvis.provider.ModelRoute
import com.bhavish.jarvis.provider.ProviderId
import com.bhavish.jarvis.provider.TaskKind

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var selectedProvider: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString("provider", "GEMINI")!!) }
            .getOrDefault(ProviderId.GEMINI)
        set(value) { prefs.edit().putString("provider", value.name).apply() }

    fun model(provider: ProviderId): String =
        prefs.getString("model_${provider.name}", provider.defaultModel).orEmpty().ifBlank { provider.defaultModel }

    fun setModel(provider: ProviderId, model: String) {
        prefs.edit().putString("model_${provider.name}", model.trim()).apply()
    }

    fun route(task: TaskKind = TaskKind.CHAT): ModelRoute {
        val selected = if (task == TaskKind.CHAT) selectedProvider else
            runCatching { ProviderId.valueOf(prefs.getString("route_${task.name}", selectedProvider.name)!!) }
                .getOrDefault(selectedProvider)
        return ModelRoute(selected, model(selected))
    }

    fun setTaskProvider(task: TaskKind, provider: ProviderId) {
        prefs.edit().putString("route_${task.name}", provider.name).apply()
    }

    var voiceName: String
        get() = prefs.getString("voice", "").orEmpty()
        set(value) { prefs.edit().putString("voice", value).apply() }

    var speakResponses: Boolean
        get() = prefs.getBoolean("speak", false)
        set(value) { prefs.edit().putBoolean("speak", value).apply() }
}
