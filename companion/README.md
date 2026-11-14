# JARVIS computer companion

Run from the `Jarvis-AI` repository root with Python 3.11 or newer. The companion is an executor; AI planning and approval happen on Android.

## Install

For core HTTPS, file access, and system status, Python's standard library and an `openssl` executable are enough. `cryptography` can create the TLS identity without an external `openssl` executable. Desktop functions need optional packages:

```sh
python -m pip install cryptography pyautogui pillow psutil playwright
python -m playwright install chromium
```

Windows window management also uses `pygetwindow`. macOS screen capture and input need Screen Recording and Accessibility permissions for the terminal or Python app in System Settings. Without those permissions, the corresponding actions return errors. On macOS, `pyobjc-framework-Quartz` enables a permission preflight check.

## Start and pair

```sh
# macOS example
python3 -m companion --root "$HOME/Desktop" --root "$HOME/Downloads"
# Windows PowerShell example
py -m companion --root "$env:USERPROFILE\Desktop" --root "$env:USERPROFILE\Downloads"
```

The default address is `https://127.0.0.1:8765`. The console prints the certificate's SHA-256 fingerprint and a one-time pairing code valid for ten minutes. Enter both in Android **Devices**. Compare the fingerprint directly with the local console before pairing. The private key and SQLite journal stay in `~/.jarvis-companion` (or `--data-dir`). Do not share that directory.

For a USB development connection, enable USB debugging on the phone, authorize the computer in Android's ADB prompt, and run:

```sh
adb reverse tcp:8765 tcp:8765
```

Then select **Prefer USB** in the phone app. A charging cable alone does not provide an app data channel. ADB reverse is a development transport and must be set again after disconnect/restart. The app attempts the USB loopback endpoint first and falls back to the saved Wi-Fi address if the USB connection fails.

For Wi-Fi, restart the agent bound to a reachable local IP, such as `--host 192.168.1.20`. Pair with `https://192.168.1.20:8765`. Restrict access with a host firewall. For remote access, use a private VPN address and bind the agent to that interface; the same certificate fingerprint and bearer token checks apply. Do not port-forward this service openly to the internet.

The current pairing code is consumed once. Restart the agent to issue a new code if another phone needs to pair. Revoking from Android deletes its server token. Keep the agent running while tasks execute; on restart, unfinished tasks are marked **interrupted** for review and are never automatically replayed.

## Supported actions and limits

Android routines use WorkManager scheduling and create a fresh task each run. Sensitive steps wait for approval on the phone every time. Reminder notifications need notification permission on Android 13 and later.

The agent accepts only the action names in `companion/contract.py`. Sensitive actions require approval recorded on the phone. File operations are limited to the `--root` folders, including resolved symlinks. No arbitrary shell command action exists. Browser automation is limited to an agent-owned Playwright browser. Screenshots are stored as authenticated artifacts. Audit records and checkpoints are in the agent's SQLite journal.

Run tests from the repository root:

```sh
python3 -m unittest discover -s companion/tests -v
```
