# Stage 3 status and validation boundary

Stage 3 is in progress. The current APK is buildable but must not be described as the final assistant.

## Implemented in this increment

- A pure presence confidence engine with expiring evidence, independent-signal requirement, arrival/departure dwell periods, cooldown, and explicit prohibition on sound-only arrival. Its transitions have unit tests. No live sensor is connected yet, so it cannot trigger routines.
- Optional manual Home Assistant desk-light controls. The Android app accepts an HTTPS origin and one `light.*` entity, stores the access token in Android Keystore-backed storage, and sends only explicit on/off requests. A configured Home Assistant server is required. Automatic arrival/departure lighting is not connected yet.

## Privacy and security

- The presence engine holds confidence values and timestamps in memory; it handles no camera images or microphone recordings.
- The current app does not request camera permission or run a background presence listener. Camera, posture, wake word, and sound-event monitoring remain unimplemented.
- Home Assistant uses Android's normal HTTPS certificate validation. Its token is encrypted through the existing KeyVault. The app restricts manual control to the configured light entity and does not expose general Home Assistant service calls.
- Computer actions still pass through the Stage 2 allowlist and approval flow. Automatic unlock is absent.

## Remaining work

Live Wi-Fi, Bluetooth, camera/person, and external sensor adapters; presence settings and visible stop control; arrival/departure orchestration; wake-on-LAN; posture and break reminders; hands-free voice and additional TTS providers; proactive task suggestions; performance/accessibility polish; full original-feature inventory; Redmi 9A, Windows, and macOS hardware tests; battery, RAM, camera, and connection measurements.

The current test/build results cover code paths only. Presence accuracy and Android 11 background behavior require the actual Redmi 9A and configured services.
