package com.bhavish.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bhavish.jarvis.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceManager(private val context: Context, private val settings: SettingsStore) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var onRecognized: ((String) -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null

    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking = _speaking.asStateFlow()
    private val _voices = MutableStateFlow<List<String>>(emptyList())
    val voices = _voices.asStateFlow()

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val engine = tts
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _speaking.value = true }
                    override fun onDone(utteranceId: String?) { _speaking.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _speaking.value = false }
                })
                _voices.value = engine?.voices?.filter { !it.isNetworkConnectionRequired }
                    ?.map { it.name }?.sorted().orEmpty()
                val selected = engine?.voices?.firstOrNull { it.name == settings.voiceName }
                if (selected != null) engine.voice = selected
                else engine?.language = Locale.getDefault()
            }
        }
    }

    fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (_listening.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("No speech recognition service is available on this phone")
            return
        }
        onRecognized = onText
        onFailure = onError
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onError(error: Int) {
                _listening.value = false
                onFailure?.invoke("Speech recognition failed (code $error). Try again.")
                sr.destroy()
                if (recognizer === sr) recognizer = null
            }
            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onRecognized?.invoke(text)
                else onFailure?.invoke("No speech was recognized")
                sr.destroy()
                if (recognizer === sr) recognizer = null
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        _listening.value = true
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        })
    }

    fun stopListening() {
        recognizer?.stopListening()
        _listening.value = false
    }

    fun speak(text: String): Boolean {
        if (!ready) return false
        _speaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
        return true
    }

    fun stopSpeaking() {
        tts?.stop()
        _speaking.value = false
    }

    fun selectVoice(name: String): Boolean {
        val chosen = tts?.voices?.firstOrNull { it.name == name } ?: return false
        val result = tts?.setVoice(chosen) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.SUCCESS) settings.voiceName = name
        return result == TextToSpeech.SUCCESS
    }

    fun close() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
