# Android-first JARVIS: Stage 1 architecture

## Scope

Add a native Android app under `android/` without changing the Python desktop runtime. The phone owns provider choice, API credentials, chat history, memory, speech input/output, and the UI. The Mac and Windows companion protocol, computer automation, always-on wake word, deep research, routines, and plugins remain later work. Their screens may explain their status but must not imply implementation.

## Layers

- **UI:** one Compose activity with HUD, conversation, memories, providers, voice, devices, tasks, routines, plugins, and settings destinations. Navigation is local state to keep dependencies small. Every interactive control has a real action.
- **Conversation:** a controller receives user text, writes a pending turn to Room, calls the selected provider, records the answer or visible error, and tracks token usage when returned. The last bounded turns are temporary model context; persisted messages are history, not automatically promoted to long-term memory.
- **Providers:** a common `AiProvider` interface with separate Gemini `generateContent`, OpenAI `chat/completions`, Anthropic `messages`, and OpenRouter `chat/completions` implementations. Selection is by provider plus model per task class. Capabilities explicitly distinguish text chat from real-time voice; Stage 1 does not implement provider-native real-time voice.
- **Keys/settings:** per-provider keys are encrypted with an Android Keystore AES-GCM key before private SharedPreferences storage. Nonsecret provider/model/voice choices use ordinary private preferences. Keys never enter Git, logs, Room, or UI state after save. Connection test makes a minimal provider request and reports a real response/error.
- **Memory:** Room stores categorized memories with timestamps and searchable text, plus conversations and messages. CRUD is explicit. Device facts, projects, instructions, preferences, summaries, tasks, and routines are allowed categories; empty categories are not fake data.
- **Voice:** Android `SpeechRecognizer` handles user-initiated push-to-talk after microphone permission; Android `TextToSpeech` reads answers using an installed voice chosen in settings. Speaking can be stopped. No background microphone capture in Stage 1.
- **Background work:** WorkManager is included as the future deferrable-work boundary. Stage 1 does not schedule fake work or keep a persistent service running.

## Data and errors

The app saves a user message before network dispatch. A provider error is shown in the conversation and leaves the message for retry. Provider requests use HTTPS, timeouts, and bounded recent history. HTTP status and a short provider error are shown without exposing keys. Usage is recorded only when the provider supplies token counts. The app runs entirely without a key for memory, navigation, settings, and speech recognition/TTS; chat clearly asks for a configured provider.

## Android compatibility

`minSdk 30` matches Android 11. A recent compile/target SDK keeps the project buildable with current tools. UI animation uses a small Compose Canvas and throttled state changes. Room and the platform speech APIs avoid large local models on the Redmi 9A. `SpeechRecognizer` needs the Android 11 recognition-service visibility query; microphone permission is requested at first use. Always-on wake word is deferred because Android 11 background microphone rules require a visible, user-started foreground service.

## Verification

Unit tests cover provider request/response mapping, selection, and memory behavior where possible. Build `assembleDebug` and `testDebugUnitTest`; install and smoke test on the Redmi 9A when it is attached. No API smoke test can pass without user-supplied keys. Retain `LICENSE` and add upstream attribution in the Android app documentation.
