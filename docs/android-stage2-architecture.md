# Stage 2: phone-orchestrated computer agents

## Design

The Android app remains the only AI planner, the task history owner, and the approval surface. The Windows and macOS agents are deterministic executors of typed commands. They never receive AI API keys, run model calls, or execute a general shell command. The original Python desktop application remains available; the new lightweight agents live in `companion/` and do not import its PyQt runtime.

The transport is HTTPS JSON with a per-agent self-signed certificate and an out-of-band SHA-256 fingerprint entered on the phone. The same endpoint works on local Wi-Fi, a private VPN address, and an ADB reverse tunnel over USB. A cable used only for charging gives no application data path. ADB reverse requires USB debugging and a trusted host, so it is a development transport; Wi-Fi is the normal fallback. No service is publicly exposed by default. Remote use requires a user-configured VPN or private tunnel and the same certificate and bearer authentication. The agent must not expose an unauthenticated control route.

## Pairing and wire contract

- On first run, an agent generates a local TLS keypair and a one-time pairing code; it prints the certificate fingerprint and code on the computer. The phone registers the address, fingerprint, code, and a human-readable name. The phone verifies the fingerprint before sending the code.
- `POST /v1/pair` exchanges the code for a random 256-bit bearer token and device ID; the code expires and is invalidated on use. Pairing attempts are rate limited. Agent stores only a hash of the token. The phone stores the token in Android Keystore-backed storage.
- Every other route requires the bearer token. `GET /v1/status` reports platform, capability availability, and a timestamp. `POST /v1/tasks` accepts a stable task ID and an ordered list of typed steps. `GET /v1/tasks/{id}` returns progress and step results. `POST /v1/tasks/{id}/cancel` cancels future steps. `DELETE /v1/devices/{id}` revokes a paired phone. `GET /v1/artifacts/{id}` returns screenshots or small generated artifacts within a size cap.
- Commands are allowlisted by name and argument schema; file paths must resolve within user-configured roots. No arbitrary shell, Python evaluation, URL-sourced instructions, or model-directed code execution. Repeated task IDs return the existing task to avoid duplicate effects after reconnect.
- Agent persists tasks and step checkpoints in SQLite. On restart, a task that was mid-step becomes `interrupted`; the phone shows it for explicit review before resuming, because replaying a click or file move blindly can duplicate an effect.
- Sensitive steps (`file.edit`, `file.move`, `file.organize`, `window.close`, `app.close`, input automation, workflows) require an explicit approval flag from the phone after the user reviews the plan. The agent also checks this flag and records it in its audit log. Read-only steps can run immediately.

## Android orchestration

Room stores registered computers, transport endpoints, tasks, and step events. The device screen adds/pairs/tests/revokes computers and selects USB tunnel or Wi-Fi endpoint. The task screen lists queued, running, completed, failed, cancelled, and interrupted work with progress, target device, history, and cancellation. A WorkManager worker executes approved plans without blocking the UI, polls the agent, and retries transient connection failures. Sensitive plans wait for a visible user approval action. A model can propose only the allowlisted JSON action schema. Its output is validated before it reaches the agent. Website and document content is data, never a higher-priority instruction.

## Platform adapters

Shared Python code handles TLS, pairing, authentication, task journal, cancellation, audit, and protocol validation. `windows.py` and `macos.py` implement OS-specific screen, input, window, app, file, browser, and system operations. Optional dependencies fail with explicit capability errors. macOS permissions for Screen Recording, Accessibility, and Automation are checked or surfaced before action. Browser automation uses Playwright with an isolated profile; it never silently takes over the user's signed-in browser.

## Validation boundaries

Linux/macOS CI-style tests can validate protocol, auth, path scoping, cancellation, journaling, and deterministic operations. Actual Windows/macOS GUI functions, USB reverse, Android-to-computer transport, and on-device behavior require those machines and the Redmi 9A. The deliverable must report each untested hardware path explicitly. Stage 3 does not start here.
