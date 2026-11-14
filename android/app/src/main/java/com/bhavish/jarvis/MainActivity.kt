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

internal val Cyan = Color(0xFF56D9F7)
internal val Blue = Color(0xFF178CB6)
internal val Background = Color(0xFF061018)
internal val Panel = Color(0xFF0C202C)
internal val Muted = Color(0xFF9CB5BF)

private enum class Page(val title: String) {
    HUD("HUD"), CHAT("Conversation"), MEMORY("Memory"), PROVIDERS("Providers"),
    VOICE("Voice"), DEVICES("Devices"), TASKS("Tasks"), ROUTINES("Routines"),
    PLUGINS("Plugins"), SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as JarvisApplication
        voice = VoiceManager(this, app.settings)
        setContent { JarvisRoot(app, voice) }
    }

    override fun onDestroy() {
        voice.close()
        super.onDestroy()
    }
}

@Composable
private fun JarvisRoot(app: JarvisApplication, voice: VoiceManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.HUD) }
    var activeConversation by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf(app.settings.selectedProvider) }
    val listening by voice.listening.collectAsState()
    val speaking by voice.speaking.collectAsState()
    val conversations by app.database.conversations().observeConversations().collectAsState(initial = emptyList())
    val messages by remember(activeConversation) {
        app.database.conversations().observeMessages(activeConversation)
    }.collectAsState(initial = emptyList())

    fun send(text: String) {
        if (busy || text.isBlank()) return
        busy = true
        page = Page.CHAT
        scope.launch {
            try {
                if (activeConversation == 0L) activeConversation = app.conversation.newConversation()
                val result = app.conversation.send(activeConversation, text)
                result.onSuccess {
                    notice = ""
                    if (app.settings.speakResponses) voice.speak(it)
                }.onFailure { notice = it.message ?: "Request failed" }
            } catch (e: Exception) {
                notice = e.message ?: "Request failed"
            } finally {
                busy = false
            }
        }
    }

    fun startVoice() {
        voice.start(onText = { send(it) }, onError = { notice = it })
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else notice = "Microphone permission is required for push-to-talk"
    }
    val onMic: () -> Unit = {
        if (listening) voice.stopListening()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Blue,
        background = Background, surface = Panel, onSurface = Color(0xFFE6F9FE))) {
        Scaffold(containerColor = Background, topBar = {
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("J.A.R.V.I.S", color = Cyan, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text(selectedProvider.label.uppercase(), color = Muted,
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Page.entries.forEach { destination ->
                        TextButton(onClick = { page = destination }) {
                            Text(destination.title, color = if (page == destination) Cyan else Muted)
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (notice.isNotBlank()) {
                    Text(notice, color = Color(0xFFFFC7B0), modifier = Modifier
                        .fillMaxWidth().padding(12.dp))
                }
                when (page) {
                    Page.HUD -> HudScreen(selectedProvider, busy, listening, speaking,
                        onMic, { page = Page.CHAT }, { page = Page.MEMORY },
                        { page = Page.PROVIDERS }, { voice.stopSpeaking() })
                    Page.CHAT -> ChatScreen(conversations, messages, activeConversation, busy, speaking,
                        onNew = { scope.launch { activeConversation = app.conversation.newConversation() } },
                        onSelect = { activeConversation = it }, onSend = ::send, onMic = onMic,
                        onStop = { voice.stopSpeaking() })
                    Page.MEMORY -> MemoryScreen(app)
                    Page.PROVIDERS -> ProvidersScreen(app, selectedProvider,
                        onSelected = { selectedProvider = it }, onNotice = { notice = it })
                    Page.VOICE -> VoiceScreen(app, voice, listening, speaking, onMic,
                        onNotice = { notice = it })
                    Page.DEVICES -> DevicesScreen(app)
                    Page.TASKS -> TasksScreen(app)
                    Page.ROUTINES -> ScheduleScreen(app)
                    Page.PLUGINS -> FutureScreen("Plugins", "Android plugin execution is planned for a later stage. Desktop Python plugins remain in the desktop app.")
                    Page.SETTINGS -> SettingsScreen(onProviders = { page = Page.PROVIDERS },
                        onVoice = { page = Page.VOICE })
                }
            }
        }
    }
}
