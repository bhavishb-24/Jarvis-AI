# JARVIS Android (Stage 1)

This native Kotlin/Jetpack Compose app makes the Android phone the local host for chat, memory, voice interaction, and provider configuration. The original Python desktop application remains at the repository root. This Android adaptation is based on Mark LVII by FatihMakes; see the root `LICENSE` (CC BY-NC 4.0) and [repository audit](../MARK_LVII_AUDIT.md).

## Requirements

- Android Studio with JDK 17, Android SDK platform 35, and Android SDK Build Tools 35.0.0; or equivalent command-line tools.
- An Android 11 (API 30) or newer phone. The intended primary device is a Redmi 9A on Android 11.
- Your own API key for at least one of Gemini, OpenAI, Anthropic Claude, or OpenRouter to send chat requests. Keys are entered inside the app and are not committed to Git.

## Build and install

Open the `android/` folder in Android Studio, let Gradle sync, and run the `app` configuration on the phone. For the command line:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable Developer options and USB debugging on the phone first. The debug APK is signed with the standard debug key; it is for local development. The app requests microphone access only when you press its microphone control. Network calls require an internet connection.

## First use

1. Open **Providers**, choose one provider, enter its model ID and API key, and save both. The key field clears after saving; the key is encrypted using Android Keystore before storage in private app preferences.
2. Press **Test connection**. This sends a short real request to the selected provider and may consume a small amount of API usage. The result or HTTP error appears at the top of the app.
3. Open **Conversation**, type a message, or use **Push to talk**. Speech recognition uses the phone's installed Android recognition service, which may use network access depending on that service.
4. Open **Voice** to choose an installed offline TTS voice, test it, and optionally enable spoken responses. The **Stop** action interrupts speech.
5. Open **Memory** to add, search, edit, or delete persistent facts. Conversation history and saved memory are separate; chat turns are not silently promoted into long-term facts.

## Design and privacy

- `minSdk 30` supports Android 11. The app uses Room for local memory and conversation history, Android Keystore for encrypted API keys, and platform STT/TTS. It does not download a local LLM.
- The four provider adapters use HTTPS and explicit model IDs. Gemini uses `generateContent`, Claude uses the Anthropic Messages API, and OpenAI/OpenRouter use chat completions. Token counts are shown when returned by a provider.
- The providers on this screen are **text chat** providers. They do not share a real-time voice API. Push-to-talk is Android STT → selected text provider → Android TTS.
- `android:allowBackup="false"` keeps the local key ciphertext and Room data out of automatic Android backup. Removing the app deletes local history and saved keys.
- The app includes WorkManager as a dependency for future deferrable jobs; Stage 1 creates no background job or microphone service.

## Current limits and remaining work

- **Not in Stage 1:** computer companion pairing/control, desktop browser/file automation from the phone, deep research, checkpointed task execution, active routines/reminders, Android plugins, screen/camera awareness, and provider-native live audio. Their screens state this explicitly.
- **Wake word:** the source project's Python `openwakeword` integration is not an Android implementation. Android 11 limits background microphone access; any future wake-word mode needs a user-started microphone foreground service, a persistent notification, battery measurements, and privacy controls. See the [audit](../MARK_LVII_AUDIT.md).
- **Testing:** unit tests and debug compilation should be run on every change. A real Redmi 9A smoke test needs the phone connected. API integration tests need user-supplied credentials, which must not enter test fixtures or build files.
- **Before release:** add Room migrations for schema changes, accessibility/contrast and small-screen validation, robust conversation retry/cancel semantics, and current target-SDK policy review.

The [architecture](../docs/android-stage1-architecture.md) and [implementation plan](../docs/android-stage1-plan.md) document how this foundation fits the later phone-first system. Do not begin Stage 2 without user approval.
