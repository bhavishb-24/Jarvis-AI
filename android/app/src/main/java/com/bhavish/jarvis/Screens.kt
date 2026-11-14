package com.bhavish.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bhavish.jarvis.data.ConversationEntity
import com.bhavish.jarvis.data.MemoryEntity
import com.bhavish.jarvis.data.MessageEntity
import com.bhavish.jarvis.provider.ProviderId
import com.bhavish.jarvis.provider.ModelRoute
import com.bhavish.jarvis.provider.TaskKind
import com.bhavish.jarvis.voice.VoiceManager
import kotlinx.coroutines.launch

@Composable
internal fun Section(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(title.uppercase(), color = Cyan, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = Muted)
    }
}

@Composable
internal fun HudScreen(provider: ProviderId, busy: Boolean, listening: Boolean, speaking: Boolean,
                      onMic: () -> Unit, onChat: () -> Unit, onMemory: () -> Unit,
                      onProviders: () -> Unit, onStopSpeaking: () -> Unit) {
    val state = when {
        listening -> "LISTENING"
        busy -> "PROCESSING"
        speaking -> "SPEAKING"
        else -> "READY"
    }
    val transition = rememberInfiniteTransition(label = "reactor")
    val pulse by transition.animateFloat(0.94f, 1.04f,
        infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ANDROID CORE // READY", color = Muted,
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension * 0.37f * pulse
                    drawCircle(Cyan.copy(alpha = 0.08f), radius * 1.18f, center)
                    drawCircle(Cyan.copy(alpha = 0.28f), radius, center, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Blue.copy(alpha = 0.7f), radius * 0.77f, center, style = Stroke(width = 5.dp.toPx()))
                    drawCircle(Cyan.copy(alpha = 0.9f), radius * 0.43f, center, style = Stroke(width = 3.dp.toPx()))
                    for (i in 0 until 24) {
                        val angle = i * Math.PI / 12
                        val from = Offset(center.x + (radius * 1.02f * kotlin.math.cos(angle)).toFloat(),
                            center.y + (radius * 1.02f * kotlin.math.sin(angle)).toFloat())
                        val to = Offset(center.x + (radius * 1.12f * kotlin.math.cos(angle)).toFloat(),
                            center.y + (radius * 1.12f * kotlin.math.sin(angle)).toFloat())
                        drawLine(Cyan.copy(alpha = 0.5f), from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("J", color = Cyan, style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Light)
                    Text(state, color = Cyan, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("VOICE • MEMORY • INTELLIGENCE", color = Muted,
                style = MaterialTheme.typography.labelSmall)
            Text("DEVICES • 0 CONNECTED", color = Muted,
                style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ACTIVE MODEL", color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(provider.label, color = Cyan, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onMic, enabled = !busy) { Text(if (listening) "Stop listening" else "Push to talk") }
            if (speaking) TextButton(onClick = onStopSpeaking) { Text("Stop speaking") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChat) { Text("Conversation") }
                OutlinedButton(onClick = onMemory) { Text("Memory") }
            }
            TextButton(onClick = onProviders) { Text("Change provider") }
        }
    }
}

@Composable
internal fun ChatScreen(conversations: List<ConversationEntity>, messages: List<MessageEntity>,
                       activeId: Long, busy: Boolean, speaking: Boolean, onNew: () -> Unit, onSelect: (Long) -> Unit,
                       onSend: (String) -> Unit, onMic: () -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Section("Conversation", "Your recent turns stay on this phone.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNew) { Text("New") }
            conversations.forEach { conversation ->
                OutlinedButton(onClick = { onSelect(conversation.id) }) {
                    Text(if (conversation.id == activeId) "● ${conversation.title}" else conversation.title,
                        maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.id }) { message ->
                Card(colors = CardDefaults.cardColors(containerColor =
                    if (message.role == "user") Color(0xFF123747) else Panel)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(if (message.role == "user") "YOU" else "JARVIS • ${message.provider}",
                            color = Cyan, style = MaterialTheme.typography.labelSmall)
                        Text(message.text, color = Color.White)
                        if (message.inputTokens != null || message.outputTokens != null)
                            Text("Tokens: ${message.inputTokens ?: "?"} in / ${message.outputTokens ?: "?"} out",
                                color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (busy) Text("Processing…", color = Cyan)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Message") },
                modifier = Modifier.weight(1f), maxLines = 4)
            Spacer(Modifier.width(6.dp))
            Column {
                Button(onClick = { val text = draft; draft = ""; onSend(text) },
                    enabled = !busy && draft.isNotBlank()) { Text("Send") }
                TextButton(onClick = onMic, enabled = !busy) { Text("Mic") }
                if (speaking) TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

@Composable
internal fun MemoryScreen(app: JarvisApplication) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("preferences") }
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    val memories by remember(query) { app.database.memories().search(query) }
        .collectAsState(initial = emptyList())
    val categories = listOf("preferences", "projects", "devices", "instructions", "summaries", "tasks", "routines", "notes")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Section("Memory", "Saved facts are separate from conversation context.")
        OutlinedTextField(query, { query = it }, label = { Text("Search memories") },
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            categories.forEach { value -> TextButton(onClick = { category = value }) {
                Text(if (category == value) "● $value" else value)
            } }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(detail, { detail = it }, label = { Text("What should JARVIS remember?") },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val current = editing
                    if (current == null) app.database.memories().insert(MemoryEntity(category = category,
                        title = title.trim(), detail = detail.trim()))
                    else app.database.memories().update(current.copy(category = category,
                        title = title.trim(), detail = detail.trim(), updatedAt = System.currentTimeMillis()))
                    title = ""; detail = ""; editing = null
                }
            }, enabled = title.isNotBlank() && detail.isNotBlank()) { Text(if (editing == null) "Save memory" else "Save changes") }
            if (editing != null) TextButton(onClick = { title = ""; detail = ""; editing = null }) { Text("Cancel edit") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.id }) { memory ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(memory.category.uppercase(), color = Cyan, style = MaterialTheme.typography.labelSmall)
                        Text(memory.title, fontWeight = FontWeight.Bold)
                        Text(memory.detail)
                        Row {
                            TextButton(onClick = {
                                editing = memory; category = memory.category; title = memory.title; detail = memory.detail
                            }) { Text("Edit") }
                            TextButton(onClick = { scope.launch { app.database.memories().delete(memory) } }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProvidersScreen(app: JarvisApplication, selected: ProviderId,
                            onSelected: (ProviderId) -> Unit, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var model by remember(selected) { mutableStateOf(app.settings.model(selected)) }
    var keyDraft by remember(selected) { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section("AI providers", "Text chat adapters. Provider-native live voice is not enabled.")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ProviderId.entries.forEach { provider ->
                TextButton(onClick = { app.settings.selectedProvider = provider; onSelected(provider) }) {
                    Text(if (provider == selected) "● ${provider.label}" else provider.label)
                }
            }
        }
        Text("${selected.label} • ${if (app.vault.configured(selected)) "key saved" else "no key"}", color = Cyan)
        OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { app.settings.setModel(selected, model); onNotice("Model saved") },
            enabled = model.isNotBlank()) { Text("Save model") }
        OutlinedTextField(keyDraft, { keyDraft = it }, label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching { app.vault.save(selected, keyDraft) }
                    .onSuccess { keyDraft = ""; onNotice("${selected.label} key saved securely") }
                    .onFailure { onNotice(it.message ?: "Key could not be saved") }
            }, enabled = keyDraft.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = { app.vault.save(selected, ""); onNotice("${selected.label} key removed") },
                enabled = app.vault.configured(selected)) { Text("Remove key") }
        }
        Button(onClick = {
            testing = true
            scope.launch {
                try {
                    app.settings.setModel(selected, model)
                    val response = app.providers.test(ModelRoute(selected, model.trim()), app.vault.read(selected))
                    onNotice("Connected to ${selected.label}: ${response.text.take(80)}")
                } catch (e: Exception) { onNotice(e.message ?: "Connection test failed") }
                finally { testing = false }
            }
        }, enabled = !testing && app.vault.configured(selected) && model.isNotBlank()) {
            Text(if (testing) "Testing…" else "Test connection")
        }
        Spacer(Modifier.height(16.dp))
        Text("TASK ROUTING", color = Cyan, fontWeight = FontWeight.Bold)
        Text("Use a different provider for future planning or summary work.", color = Muted)
        TaskKind.entries.filter { it != TaskKind.CHAT }.forEach { task ->
            Text(task.name, color = Muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ProviderId.entries.forEach { provider ->
                    val active = app.settings.route(task).provider == provider
                    TextButton(onClick = { app.settings.setTaskProvider(task, provider); onNotice("${task.name.lowercase()} uses ${provider.label}") }) {
                        Text(if (active) "● ${provider.label}" else provider.label)
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoiceScreen(app: JarvisApplication, voice: VoiceManager, listening: Boolean,
                        speaking: Boolean, onMic: () -> Unit, onNotice: (String) -> Unit) {
    val voices by voice.voices.collectAsState()
    var speak by remember { mutableStateOf(app.settings.speakResponses) }
    var selected by remember { mutableStateOf(app.settings.voiceName) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Section("Voice", "Push-to-talk uses your Android speech service. Replies use Android TTS.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(speak, { speak = it; app.settings.speakResponses = it })
            Text("Read assistant replies aloud")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMic) { Text(if (listening) "Stop listening" else "Push to talk") }
            OutlinedButton(onClick = {
                if (!voice.speak("JARVIS voice test.")) onNotice("Text-to-speech is not ready")
            }) { Text("Test voice") }
            if (speaking) TextButton(onClick = { voice.stopSpeaking() }) { Text("Stop") }
        }
        Spacer(Modifier.height(8.dp))
        Text("AVAILABLE OFFLINE VOICES", color = Cyan)
        if (voices.isEmpty()) Text("No offline TTS voices are installed or the engine is still starting.", color = Muted)
        LazyColumn {
            items(voices) { name ->
                TextButton(onClick = {
                    if (voice.selectVoice(name)) { selected = name; onNotice("Voice selected: $name") }
                    else onNotice("Voice could not be selected")
                }) { Text(if (name == selected) "● $name" else name) }
            }
        }
    }
}

@Composable
internal fun FutureScreen(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title.uppercase(), color = Cyan, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        Text(detail, color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SettingsScreen(onProviders: () -> Unit, onVoice: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Section("Settings", "JARVIS Android foundation • Stage 1")
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("DEVICE", color = Cyan)
                Text("Android ${android.os.Build.VERSION.RELEASE} • ${android.os.Build.MODEL}")
                Text("The phone stores memory and orchestrates AI requests.", color = Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onProviders) { Text("AI providers") }
        OutlinedButton(onClick = onVoice) { Text("Voice settings") }
        Spacer(Modifier.height(24.dp))
        Text("Based on Mark LVII by FatihMakes. Original source is licensed CC BY-NC 4.0. Android adaptation by this fork.",
            color = Muted)
    }
}
