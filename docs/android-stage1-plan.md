# Android Stage 1 implementation plan

**Goal:** Produce a buildable Android 11-compatible JARVIS foundation while preserving the desktop code.

**Architecture:** See [android-stage1-architecture.md](android-stage1-architecture.md).

**Tech stack:** Kotlin, Jetpack Compose, Room, Android Keystore, Android SpeechRecognizer/TextToSpeech, Gradle, AndroidX WorkManager.

- [x] Create Gradle Android project, manifest, theme, and baseline build.
- [x] Add provider contract, separate four HTTP implementations, model selection, error mapping, usage accounting, and tests.
- [x] Add encrypted key store and provider configuration/connection test.
- [x] Add Room memories and conversation/history DAOs. Database behavior still needs device-level tests.
- [x] Add conversation controller with bounded context and visible errors.
- [x] Add Android speech recognition and TTS with user-driven permission and voice selection.
- [x] Add Compose HUD, main navigation, working settings/memory/provider/conversation screens, and honest future screens.
- [x] Add setup/install documentation and remaining-work ledger.
- [x] Run unit tests, debug build, and lint.
- [ ] Install and smoke test on the Redmi 9A when it is connected.
