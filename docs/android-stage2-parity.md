# Stage 2 feature parity and remaining work

| Capability | Stage 2 state | Notes |
| --- | --- | --- |
| Desktop control | Implemented in companion adapters | Platform permissions and optional GUI packages required |
| Browser automation | Implemented in companion adapters | Agent-owned Playwright browser; desktop browser sessions are not reused |
| File processing | Implemented in scoped roots | Text preview and deterministic summaries; no arbitrary formats or shell |
| Screen awareness | Screenshot capture and Android display | Model vision context is not wired yet |
| Background tasks | Android WorkManager and companion threads | Idempotent task IDs, progress, cancellation, interrupted review |
| Deep research and source verification | Stage 1 chat provider only | Dedicated multi-source research workflow not implemented |
| Task checkpoints | Implemented | Companion SQLite step checkpoints; interrupted tasks require review |
| Routines and reminders | Placeholder | Scheduling and permissions not implemented |
| Plugin system | Stage 1 desktop plugins remain | No Android plugin registry or capability routing yet |
| Remote dashboard | Android Devices and Tasks screens | No separate web dashboard |
| Voice customization | Preserved from Stage 1 | Android voice settings remain |
| System controls | Read-only status and apps, app/window actions | No unrestricted shell or privileged OS control |

Hardware tests on an Android phone and Windows laptop remain required. The code has been compiled and unit tested on macOS; actual USB, Wi-Fi, permission, GUI, and remote VPN paths need device tests.
