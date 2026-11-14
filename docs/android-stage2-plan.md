# Stage 2 implementation plan

**Goal:** Add authenticated, resumable computer execution while keeping Android as the AI and task host.

**Architecture:** [android-stage2-architecture.md](android-stage2-architecture.md).

**Tech stack:** Android Kotlin/Room/WorkManager; Python 3.11+ stdlib HTTP/SQLite/SSL; optional PyAutoGUI, mss, Playwright, psutil, cryptography.

- [x] Define protocol schema, allowlisted actions, sensitivity policy, and transport documentation.
- [x] Implement shared companion HTTPS server, pairing, auth, revocation, task journal, cancellation, and tests.
- [x] Implement and unit test Windows platform adapter; hardware validation remains.
- [x] Implement and unit test macOS platform adapter; GUI permission validation remains.
- [x] Add Android device registration, certificate-fingerprint validation, encrypted token storage, status/revocation, and USB/Wi-Fi endpoint switching.
- [x] Add Android task database, worker, lifecycle, history, cancellation, and plan approval UI.
- [x] Add validated natural-language action planning through the selected AI provider.
- [x] Add authenticated screenshot artifacts and Android display.
- [ ] Add a bounded image context path for vision-enabled planning.
- [x] Add basic WorkManager routines and local reminders; sensitive routine runs wait for phone approval.
- [ ] Add research with source checks and plugin capability routing without independent agent reasoning.
- [x] Run Python tests and Android unit tests/build/lint.
- [ ] Run Android, Windows, GUI, USB, Wi-Fi, and VPN hardware smoke tests when equipment is available; document gaps.
