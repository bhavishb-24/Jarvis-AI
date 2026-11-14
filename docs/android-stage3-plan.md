# Stage 3 implementation plan

**Goal:** Add configurable, privacy-conscious desk presence and arrival/departure automation while preserving the Android phone as the planner and approval surface.

**Architecture:** Sensor adapters emit short-lived evidence into a pure presence engine. The engine emits stable arrival/departure transitions only after independent evidence and dwell periods. Android invokes existing approved task plans, optional Home Assistant actions, and configured voice responses. Camera frames stay local and are discarded unless the user explicitly captures one.

**Platform constraint:** A user-visible foreground camera/microphone session is required for dependable desk-mounted sensing; Android 11 restricts camera and microphone access when a foreground service was started from the background. Wi-Fi SSID access requires location permission. Exact behavior must be validated on the Redmi 9A.

## Delivery sequence

1. Implement and unit test confidence, signal freshness, dwell, cooldown, departure confirmation, and the rule that sound alone cannot cause arrival.
2. Add explicit opt-in configuration and a visible presence status with immediate stop control. Begin with Wi-Fi and paired-computer reachability; add Bluetooth proximity only after the phone's scan behavior can be measured.
3. Add local camera motion/person/posture signals in a visible session. Do not save frames by default. Measure CPU, RAM, heat, and battery before enabling continuous mode.
4. Add configurable arrival/departure actions through existing approved task plans. Never include automatic unlock. Add wake-on-LAN only for a user-configured MAC address and network.
5. Add Home Assistant connection and explicit entity allowlist. Keep credentials in Android Keystore-backed storage; make manual light controls available when presence is off.
6. Improve voice options and explain the Android hands-free limits. Add background listening only through a user-started, visible service that satisfies platform restrictions.
7. Audit security and feature parity, run Android build/unit/lint and companion tests, then perform real Redmi 9A, Mac, and Windows hardware tests before calling Stage 3 complete.

## Verification gate

The software-only tests prove deterministic logic and build compatibility. Arrival accuracy, camera performance, voice reliability, Bluetooth range, USB recovery, and battery impact require physical hardware and cannot be certified by unit tests.
