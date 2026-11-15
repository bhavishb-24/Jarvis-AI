"""Run the Windows or macOS companion from the repository root."""
import argparse
import platform
from pathlib import Path
import signal
import threading
from urllib.parse import quote

from .server import CompanionServer


def main():
    parser = argparse.ArgumentParser(description="JARVIS computer companion")
    parser.add_argument("--root", action="append", required=True,
                        help="Folder the phone may read or modify; repeat for multiple folders")
    parser.add_argument("--data-dir", type=Path, default=Path.home() / ".jarvis-companion")
    parser.add_argument("--host", default="127.0.0.1",
                        help="Bind address; use a LAN/VPN address only on a trusted network")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if platform.system() == "Windows":
        from .windows import WindowsAdapter
        adapter = WindowsAdapter([Path(root) for root in args.root])
    elif platform.system() == "Darwin":
        from .macos import MacAdapter
        adapter = MacAdapter([Path(root) for root in args.root])
    else:
        parser.error("The companion supports Windows and macOS")
    server = CompanionServer(adapter, args.data_dir, args.host, args.port).start()
    print("JARVIS companion ready", flush=True)
    print("HTTPS address:", server.address, flush=True)
    print("SHA-256 fingerprint:", server.fingerprint, flush=True)
    print("One-time pairing code:", server.pairing_code, flush=True)
    parts = ("JARVIS_PAIR_V1", server.address, server.fingerprint, server.pairing_code)
    print("JARVIS pairing text:", "|".join(quote(part, safe="") for part in parts), flush=True)
    print("Allowed file roots:", ", ".join(str(root) for root in adapter.allowed_roots), flush=True)
    print("Available actions:", ", ".join(adapter.capabilities()), flush=True)
    print("Keep this window open. Press Ctrl+C to stop.", flush=True)
    stopped = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    try:
        stopped.wait()
    finally:
        server.stop()


if __name__ == "__main__":
    main()
