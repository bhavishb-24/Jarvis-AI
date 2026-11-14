# Stage 1 verification record

Date: 2026-11-14 (America/New_York)

## Commands and results

From `android/`, with JDK 17 and SDK platform 35 available:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The checked-in Gradle wrapper command exited 0 after the final UI split: **BUILD SUCCESSFUL in 37s**. JUnit reported **5 tests, 0 failures, 0 errors, 0 skipped** (`ProviderContractTest`). The debug APK was generated at `app/build/outputs/apk/debug/app-debug.apk` (about 25 MB). Android lint completed with **0 errors and 11 warnings**: ten suggest newer library versions and one recommends KSP instead of kapt. No manifest or source-code lint errors remain.

`adb devices` ran successfully outside the sandbox and listed **no attached devices**. The APK has therefore not been installed or smoke tested on the Redmi 9A. Provider connection tests could not be run because no user API keys were supplied; the in-app Test connection button makes a real request when a key is configured.

The temporary build tools were JDK 17.0.21, Gradle 8.9, Android SDK platform 35, and Android Build Tools 34/35. The Gradle wrapper and Room schema version 1 are checked into the project. The original Python application was not modified.

## Coverage and remaining verification

The JVM tests verify task routing, provider capability declarations, and request payload shape for Gemini, Claude, and OpenAI-compatible chat. They do not prove network integration, Android Keystore behavior, Room persistence on a device, speech recognition, TTS, or small-screen layout. Those require a connected device or emulator and, for provider calls, credentials. Run these as the next validation step before calling the app production ready.
