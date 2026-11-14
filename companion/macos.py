"""Deterministic macOS executor for the Stage 2 phone companion.

GUI libraries are loaded only when used, so file actions work without a
desktop session. No action interprets document or web content as commands.
"""

import base64
import io
import os
import platform
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from .contract import ACTIONS
from .system_status import collect_system_status


MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_RESULTS = 100
MAX_SCREEN_BYTES = 5 * 1024 * 1024


class CapabilityError(RuntimeError):
    pass


def _screen_permission() -> Optional[bool]:
    try:
        import Quartz
        return bool(Quartz.CGPreflightScreenCaptureAccess())
    except (ImportError, AttributeError):
        return None


def _accessibility_permission() -> Optional[bool]:
    try:
        import Quartz
        return bool(Quartz.AXIsProcessTrusted())
    except (ImportError, AttributeError):
        return None


def _load_pyautogui():
    try:
        import pyautogui
        return pyautogui
    except ImportError:
        return None


def _load_playwright():
    try:
        from playwright.sync_api import sync_playwright
        return sync_playwright
    except ImportError:
        return None


class MacAdapter:
    platform = "macos"

    def __init__(self, allowed_roots: List[Path]):
        self.allowed_roots = [Path(root).expanduser().resolve() for root in allowed_roots]
        self._browser_lock = threading.RLock()
        self._browser_context = None
        self._browser_page = None
        self._playwright = None
        self._browser_profile = None

    def capabilities(self) -> List[str]:
        supported = set(ACTIONS)
        if _load_pyautogui() is None or _screen_permission() is False:
            supported.difference_update({"screen.capture", "screen.update"})
        if _load_pyautogui() is None or _accessibility_permission() is False:
            supported.difference_update({a for a in ACTIONS if a.startswith("input.")})
        if _load_playwright() is None:
            supported.difference_update({a for a in ACTIONS if a.startswith("browser.")})
        return sorted(supported)

    def execute(self, action: str, args: dict, cancel: threading.Event) -> Dict[str, Any]:
        if action not in ACTIONS:
            return self._error("unknown action")
        if not isinstance(args, dict):
            return self._error("args must be an object")
        if cancel.is_set():
            return self._error("cancelled")
        try:
            group, operation = action.split(".", 1)
            data = getattr(self, "_" + group)(operation, args, cancel)
            return {"ok": True, "data": data, "error": ""}
        except (ValueError, FileNotFoundError, FileExistsError, PermissionError,
                IsADirectoryError, NotADirectoryError, CapabilityError, OSError) as exc:
            return self._error(str(exc))
        except Exception as exc:
            # GUI frameworks can raise their own exception types. Keep the
            # executor boundary deterministic and avoid leaking tracebacks.
            return self._error("{}: {}".format(type(exc).__name__, exc))

    @staticmethod
    def _error(message: str) -> Dict[str, Any]:
        return {"ok": False, "data": None, "error": message}

    @staticmethod
    def _required(args: dict, key: str, max_length: int = 4096) -> str:
        value = args.get(key)
        if not isinstance(value, str) or not value or len(value) > max_length:
            raise ValueError("{} must be a nonempty string".format(key))
        return value

    @classmethod
    def _app_name(cls, args: dict) -> str:
        return cls._required({"app": args.get("app", args.get("name"))}, "app", 256)

    @staticmethod
    def _integer(args: dict, key: str, default=None, minimum=-100000, maximum=100000) -> int:
        value = args.get(key, default)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or int(value) != value:
            raise ValueError("{} must be an integer".format(key))
        value = int(value)
        if not minimum <= value <= maximum:
            raise ValueError("{} is out of range".format(key))
        return value

    def _path(self, raw: str, must_exist: bool = False) -> Path:
        if not self.allowed_roots:
            raise PermissionError("no file roots configured")
        candidate = Path(raw).expanduser()
        if not candidate.is_absolute():
            raise ValueError("path must be absolute")
        resolved = candidate.resolve(strict=False)
        if not any(resolved == root or root in resolved.parents for root in self.allowed_roots):
            raise PermissionError("path is outside allowed roots")
        if must_exist and not resolved.exists():
            raise FileNotFoundError(str(resolved))
        return resolved

    @staticmethod
    def _check_cancel(cancel: threading.Event):
        if cancel.is_set():
            raise ValueError("cancelled")

    def _file(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        if operation == "search":
            query = self._required(args, "query", 256).casefold()
            root = self._path(args["path"], True) if "path" in args else None
            roots = [root] if root else self.allowed_roots
            paths = []
            for base in roots:
                if not base.exists():
                    continue
                iterator = (base,) if base.is_file() else base.rglob("*")
                for path in iterator:
                    self._check_cancel(cancel)
                    if len(paths) >= MAX_RESULTS:
                        break
                    if path.is_file() and query in path.name.casefold():
                        try:
                            paths.append(str(self._path(str(path), True)))
                        except PermissionError:
                            continue
            return {"paths": sorted(set(paths))[:MAX_RESULTS]}

        if operation == "organize":
            folder = self._path(self._required(args, "path"), True)
            if not folder.is_dir():
                raise NotADirectoryError(str(folder))
            moved = []
            for source in sorted(folder.iterdir()):
                self._check_cancel(cancel)
                if not source.is_file() or source.is_symlink():
                    continue
                suffix = source.suffix.lower().lstrip(".") or "other"
                destination = self._path(str(folder / suffix / source.name))
                if destination.exists():
                    continue
                destination.parent.mkdir(exist_ok=True)
                source.rename(destination)
                moved.append({"source": str(source), "destination": str(destination)})
                if len(moved) >= MAX_RESULTS:
                    break
            return {"moved": moved}

        if operation == "move":
            source = self._path(self._required(args, "source"), True)
            destination = self._path(self._required(args, "destination"))
            if source in self.allowed_roots:
                raise PermissionError("cannot move a configured root")
            if source.is_dir() and source in destination.parents:
                raise ValueError("cannot move a directory into itself")
            if destination.exists():
                raise FileExistsError(str(destination))
            self._check_cancel(cancel)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(destination))
            return {"source": str(source), "destination": str(destination)}

        path = self._path(self._required(args, "path"), operation != "create")
        if operation == "read" or operation == "summarize":
            if not path.is_file():
                raise IsADirectoryError(str(path))
            if path.stat().st_size > MAX_FILE_BYTES:
                raise ValueError("file exceeds 2 MiB read limit")
            content = path.read_text(encoding="utf-8")
            if operation == "read":
                return {"path": str(path), "content": content}
            lines = [line.strip() for line in content.splitlines() if line.strip()]
            return {"path": str(path), "characters": len(content),
                    "lines": len(content.splitlines()),
                    "summary": " ".join(lines[:3])[:500], "preview": content[:1000]}

        if operation in ("create", "edit"):
            content = args.get("content")
            if not isinstance(content, str) or len(content.encode("utf-8")) > MAX_FILE_BYTES:
                raise ValueError("content must be text of at most 2 MiB")
            if operation == "create" and path.exists():
                raise FileExistsError(str(path))
            if operation == "edit" and not path.is_file():
                raise IsADirectoryError(str(path))
            self._check_cancel(cancel)
            path.parent.mkdir(parents=True, exist_ok=True)
            if operation == "create":
                with path.open("x", encoding="utf-8") as handle:
                    handle.write(content)
            else:
                with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                                 prefix=".companion-", delete=False) as handle:
                    handle.write(content)
                    temporary = Path(handle.name)
                try:
                    self._check_cancel(cancel)
                    os.chmod(temporary, path.stat().st_mode)
                    temporary.replace(path)
                finally:
                    temporary.unlink(missing_ok=True)
            return {"path": str(path), "bytes": len(content.encode("utf-8"))}
        raise ValueError("unsupported file action")

    def _screen(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        if _screen_permission() is False:
            raise CapabilityError("Screen Recording permission is required in macOS Privacy & Security settings")
        pyautogui = _load_pyautogui()
        if pyautogui is None:
            raise CapabilityError("screen capture requires optional PyAutoGUI and Pillow")
        self._check_cancel(cancel)
        try:
            shot = pyautogui.screenshot()
            buffer = io.BytesIO()
            shot.convert("RGB").save(buffer, format="JPEG", quality=75)
        except Exception as exc:
            raise CapabilityError("Screen Recording capture failed: {}".format(exc)) from exc
        payload = buffer.getvalue()
        if len(payload) > MAX_SCREEN_BYTES:
            raise ValueError("screenshot exceeds 5 MiB limit")
        return {"mime_type": "image/jpeg", "base64": base64.b64encode(payload).decode("ascii"),
                "width": shot.width, "height": shot.height}

    def _input(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        if _accessibility_permission() is False:
            raise CapabilityError("Accessibility permission is required in macOS Privacy & Security settings")
        pyautogui = _load_pyautogui()
        if pyautogui is None:
            raise CapabilityError("input automation requires optional PyAutoGUI")
        self._check_cancel(cancel)
        try:
            if operation == "move":
                pyautogui.moveTo(self._integer(args, "x"), self._integer(args, "y"))
            elif operation == "click":
                if args.get("button", "left") not in ("left", "middle", "right"):
                    raise ValueError("button must be left, middle, or right")
                pyautogui.click(self._integer(args, "x"), self._integer(args, "y"),
                                button=args.get("button", "left"))
            elif operation == "scroll":
                pyautogui.scroll(self._integer(args, "amount", default=1, minimum=-100, maximum=100))
            elif operation == "type":
                pyautogui.write(self._required(args, "text", 10000), interval=0.005)
            elif operation == "hotkey":
                keys = args.get("keys")
                if not isinstance(keys, list) or not 1 <= len(keys) <= 4 or not all(
                        isinstance(key, str) and key in pyautogui.KEYBOARD_KEYS for key in keys):
                    raise ValueError("keys must be a list of 1-4 supported key names")
                pyautogui.hotkey(*keys)
            else:
                raise ValueError("unsupported input action")
        except ValueError:
            raise
        except Exception as exc:
            raise CapabilityError("Accessibility input failed: {}".format(exc)) from exc
        return {"action": operation}

    @staticmethod
    def _applescript(script: str, *arguments: str) -> str:
        if platform.system() != "Darwin":
            raise CapabilityError("macOS Automation requires macOS")
        result = subprocess.run(["/usr/bin/osascript", "-e", script, *arguments],
                                capture_output=True, text=True, timeout=15, check=False)
        if result.returncode:
            raise CapabilityError("macOS Automation permission or application action failed: {}".format(
                result.stderr.strip()[:500] or "osascript failed"))
        return result.stdout.strip()

    def _window(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        if operation == "list":
            try:
                import Quartz
            except ImportError as exc:
                raise CapabilityError("window listing requires optional PyObjC Quartz") from exc
            windows = Quartz.CGWindowListCopyWindowInfo(Quartz.kCGWindowListOptionOnScreenOnly,
                                                        Quartz.kCGNullWindowID)
            return {"windows": [{"id": int(item.get("kCGWindowNumber", 0)),
                                 "title": str(item.get("kCGWindowName", "")),
                                 "app": str(item.get("kCGWindowOwnerName", ""))}
                                for item in windows[:MAX_RESULTS]]}
        app = self._app_name(args)
        if operation == "activate":
            self._applescript("on run argv\nset appName to item 1 of argv\ntell application appName to activate\nend run", app)
        elif operation == "close":
            if _accessibility_permission() is False:
                raise CapabilityError("Accessibility permission is required to close windows")
            self._applescript("on run argv\nset appName to item 1 of argv\ntell application \"System Events\" to tell process appName to perform action \"AXClose\" of front window\nend run", app)
        else:
            raise ValueError("unsupported window action")
        return {"app": app}

    def _app(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        app = self._app_name(args)
        if operation == "launch":
            if platform.system() != "Darwin":
                raise CapabilityError("app launch requires macOS")
            result = subprocess.run(["/usr/bin/open", "-a", app], capture_output=True,
                                    text=True, timeout=15, check=False)
            if result.returncode:
                raise CapabilityError(result.stderr.strip()[:500] or "application could not be launched")
        elif operation == "close":
            self._applescript("on run argv\nset appName to item 1 of argv\ntell application appName to quit\nend run", app)
        else:
            raise ValueError("unsupported app action")
        return {"app": app}

    def _project(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        path = self._path(self._required(args, "path"), True)
        if platform.system() != "Darwin":
            raise CapabilityError("project opening requires macOS")
        result = subprocess.run(["/usr/bin/open", str(path)], capture_output=True,
                                text=True, timeout=15, check=False)
        if result.returncode:
            raise CapabilityError(result.stderr.strip()[:500] or "project could not be opened")
        return {"path": str(path)}

    def _system(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        if operation == "status":
            return dict(collect_system_status(self.platform),
                        os_version=platform.mac_ver()[0],
                        screen_recording=_screen_permission(),
                        accessibility=_accessibility_permission())
        if operation == "apps":
            applications = Path("/Applications")
            names = sorted(path.stem for path in applications.glob("*.app")) if applications.exists() else []
            return {"apps": names[:MAX_RESULTS]}
        raise ValueError("unsupported system action")

    @staticmethod
    def _url(value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
            raise ValueError("url must be an HTTP(S) URL without embedded credentials")
        return value

    def _browser_page_for_action(self):
        if self._browser_page is None:
            raise CapabilityError("browser is not open; use browser.open first")
        return self._browser_page

    def _browser(self, operation: str, args: dict, cancel: threading.Event) -> dict:
        loader = _load_playwright()
        if loader is None:
            raise CapabilityError("browser automation requires optional Playwright and its Chromium browser")
        with self._browser_lock:
            self._check_cancel(cancel)
            if operation == "open":
                url = self._url(self._required(args, "url", 2048))
                if self._browser_page is None:
                    self._browser_profile = tempfile.TemporaryDirectory(prefix="jarvis-companion-browser-")
                    self._playwright = loader().start()
                    try:
                        self._browser_context = self._playwright.chromium.launch_persistent_context(
                            self._browser_profile.name, headless=True)
                        self._browser_page = self._browser_context.new_page()
                    except Exception:
                        self.close()
                        raise
                page = self._browser_page
                page.goto(url, wait_until="domcontentloaded", timeout=15000)
            else:
                page = self._browser_page_for_action()
                if operation == "navigate":
                    page.goto(self._url(self._required(args, "url", 2048)),
                              wait_until="domcontentloaded", timeout=15000)
                elif operation == "click":
                    page.locator(self._required(args, "selector", 512)).first.click(timeout=10000)
                elif operation == "type":
                    page.locator(self._required(args, "selector", 512)).first.fill(
                        self._required(args, "text", 10000), timeout=10000)
                elif operation == "extract":
                    selector = args.get("selector", "body")
                    if not isinstance(selector, str) or not selector or len(selector) > 512:
                        raise ValueError("selector must be a nonempty string")
                    content = page.locator(selector).first.inner_text(timeout=10000)
                    return {"url": page.url, "text": content[:20000], "truncated": len(content) > 20000}
                elif operation == "back":
                    page.go_back(wait_until="domcontentloaded", timeout=15000)
                elif operation == "forward":
                    page.go_forward(wait_until="domcontentloaded", timeout=15000)
                else:
                    raise ValueError("unsupported browser action")
            return {"url": page.url, "title": page.title()}

    def close(self):
        with self._browser_lock:
            if self._browser_context is not None:
                self._browser_context.close()
            if self._playwright is not None:
                self._playwright.stop()
            if self._browser_profile is not None:
                self._browser_profile.cleanup()
            self._browser_context = self._browser_page = self._playwright = self._browser_profile = None
