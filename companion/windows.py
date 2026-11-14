"""Deterministic Windows operations for the phone companion.

The transport owns authentication and approval. This adapter validates every
argument again at the execution boundary and never invokes a shell.
"""
from __future__ import annotations

import base64
import importlib
import io
import os
from pathlib import Path
import shutil
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from typing import Any
from urllib.parse import urlparse

from .contract import ACTIONS


MAX_TEXT = 1_048_576
MAX_RESULTS = 100
MAX_SCREEN = 8_388_608


class Unavailable(RuntimeError):
    pass


class WindowsAdapter:
    platform = "windows"

    def __init__(self, allowed_roots: list[Path]):
        self.allowed_roots = tuple(Path(root).expanduser().resolve() for root in allowed_roots)
        if not self.allowed_roots:
            raise ValueError("at least one allowed root is required")
        self._browser_worker: ThreadPoolExecutor | None = None
        self._browser_state: dict[str, Any] = {}
        self._browser_lock = threading.Lock()

    def capabilities(self) -> list[str]:
        result = ["file.search", "file.read", "file.create", "file.edit", "file.move",
                  "file.organize", "file.summarize", "system.status"]
        if sys.platform == "win32":
            result += ["app.launch", "project.open"]
            for module, actions in (
                ("pyautogui", ["screen.capture", "screen.update", "input.move", "input.click",
                               "input.scroll", "input.type", "input.hotkey"]),
                ("pygetwindow", ["window.list", "window.activate", "window.close"]),
                ("psutil", ["app.close", "system.apps"]),
                ("playwright.sync_api", ["browser.open", "browser.navigate", "browser.click",
                                         "browser.type", "browser.extract", "browser.back", "browser.forward"]),
            ):
                try:
                    importlib.import_module(module)
                except ImportError:
                    continue
                result.extend(actions)
        return result

    def execute(self, action: str, args: dict, cancel: threading.Event) -> dict:
        try:
            if action not in ACTIONS:
                raise ValueError("unknown action")
            if not isinstance(args, dict):
                raise ValueError("args must be an object")
            if cancel.is_set():
                raise RuntimeError("cancelled")
            group, name = action.split(".", 1)
            handler = getattr(self, f"_{group}_{name}", None)
            if handler is None:
                raise Unavailable(f"{action} is unavailable")
            data = handler(args, cancel)
            return {"ok": True, "data": data, "error": ""}
        except Exception as exc:
            return {"ok": False, "data": None, "error": str(exc)}

    @staticmethod
    def _string(args: dict, key: str, *, limit: int = 4096, required: bool = True) -> str:
        value = args.get(key)
        if value is None and not required:
            return ""
        if not isinstance(value, str) or (required and not value) or len(value) > limit:
            raise ValueError(f"{key} must be a nonempty string of at most {limit} characters")
        return value

    @staticmethod
    def _number(args: dict, key: str, *, minimum: float, maximum: float, default=None) -> float:
        value = args.get(key, default)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not minimum <= value <= maximum:
            raise ValueError(f"{key} must be between {minimum} and {maximum}")
        return value

    @staticmethod
    def _module(name: str):
        if sys.platform != "win32":
            raise Unavailable("Windows desktop operations require Windows")
        try:
            return importlib.import_module(name)
        except ImportError as exc:
            raise Unavailable(f"{name} is unavailable; install the optional dependency") from exc

    def _path(self, value: Any, *, must_exist: bool = False) -> Path:
        if not isinstance(value, str) or not value or len(value) > 4096 or "\x00" in value:
            raise ValueError("path must be a nonempty string")
        raw = Path(value).expanduser()
        if not raw.is_absolute():
            raise ValueError("path must be absolute")
        path = raw.resolve(strict=False)
        if not any(path == root or root in path.parents for root in self.allowed_roots):
            raise ValueError("path is outside allowed roots")
        if must_exist and not path.exists():
            raise ValueError("path does not exist")
        return path

    @staticmethod
    def _text_file(path: Path) -> str:
        if not path.is_file() or path.stat().st_size > MAX_TEXT:
            raise ValueError("file must be a regular file no larger than 1 MiB")
        try:
            return path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise ValueError("file is not UTF-8 text") from exc

    def _file_read(self, args, cancel):
        path = self._path(self._string(args, "path"), must_exist=True)
        return {"path": str(path), "content": self._text_file(path)}

    def _file_create(self, args, cancel):
        path = self._path(self._string(args, "path"))
        content = self._string(args, "content", limit=MAX_TEXT, required=False)
        if len(content.encode("utf-8")) > MAX_TEXT:
            raise ValueError("created file exceeds 1 MiB")
        if not path.parent.is_dir():
            raise ValueError("parent directory does not exist")
        with path.open("x", encoding="utf-8") as output:
            output.write(content)
        return {"path": str(path), "bytes": len(content.encode("utf-8"))}

    def _file_edit(self, args, cancel):
        path = self._path(self._string(args, "path"), must_exist=True)
        original = self._text_file(path)
        if "old" in args:
            old = self._string(args, "old")
            new = self._string(args, "new", limit=MAX_TEXT, required=False)
            if original.count(old) != 1:
                raise ValueError("old text must occur exactly once")
            content = original.replace(old, new, 1)
        else:
            content = self._string(args, "content", limit=MAX_TEXT, required=False)
        if len(content.encode("utf-8")) > MAX_TEXT:
            raise ValueError("edited file exceeds 1 MiB")
        # Same-directory replacement prevents a partial write on failure.
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent,
                                         prefix=".jarvis-", delete=False) as output:
            temporary = Path(output.name)
            try:
                output.write(content)
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise
        try:
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
        return {"path": str(path), "bytes": len(content.encode("utf-8"))}

    def _move(self, source: Path, destination: Path):
        if source == destination or not source.exists() or destination.exists() or not destination.parent.is_dir():
            raise ValueError("source and destination must differ; source and destination parent must exist; destination must be unused")
        shutil.move(str(source), str(destination))
        return {"source": str(source), "destination": str(destination)}

    def _file_move(self, args, cancel):
        return self._move(self._path(self._string(args, "source"), must_exist=True),
                          self._path(self._string(args, "destination")))

    def _file_organize(self, args, cancel):
        moves = args.get("moves")
        if not isinstance(moves, list) or not 1 <= len(moves) <= 100:
            raise ValueError("moves must contain 1 to 100 source/destination objects")
        pairs = []
        for item in moves:
            if not isinstance(item, dict):
                raise ValueError("each move must be an object")
            source = self._path(self._string(item, "source"), must_exist=True)
            destination = self._path(self._string(item, "destination"))
            if source == destination or destination.exists() or not destination.parent.is_dir():
                raise ValueError("invalid or occupied destination")
            pairs.append((source, destination))
        if len({p for pair in pairs for p in pair}) != len(pairs) * 2:
            raise ValueError("move paths must be unique")
        results = []
        for source, destination in pairs:
            if cancel.is_set():
                raise RuntimeError("cancelled after %d moves" % len(results))
            results.append(self._move(source, destination))
        return {"moves": results}

    def _file_search(self, args, cancel):
        root = self._path(self._string(args, "root"), must_exist=True)
        query = self._string(args, "query", limit=256).casefold()
        if not root.is_dir():
            raise ValueError("root must be a directory")
        matches = []
        for directory, dirs, files in os.walk(root, followlinks=False):
            if cancel.is_set():
                raise RuntimeError("cancelled")
            dirs[:] = [name for name in dirs if self._within(Path(directory) / name)]
            for name in files:
                if query in name.casefold():
                    path = Path(directory) / name
                    if self._within(path):
                        matches.append(str(path.resolve()))
                        if len(matches) >= MAX_RESULTS:
                            return {"matches": matches, "truncated": True}
        return {"matches": matches, "truncated": False}

    def _within(self, path: Path) -> bool:
        resolved = path.resolve(strict=False)
        return any(resolved == root or root in resolved.parents for root in self.allowed_roots)

    def _file_summarize(self, args, cancel):
        path = self._path(self._string(args, "path"), must_exist=True)
        content = self._text_file(path)
        lines = content.splitlines()
        return {"path": str(path), "characters": len(content), "lines": len(lines),
                "preview": content[:2000], "summary_kind": "deterministic_preview"}

    def _screen_capture(self, args, cancel):
        pyautogui = self._module("pyautogui")
        image = pyautogui.screenshot()
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        data = buffer.getvalue()
        if len(data) > MAX_SCREEN:
            raise ValueError("screenshot exceeds 8 MiB")
        return {"mime_type": "image/png", "base64": base64.b64encode(data).decode("ascii")}

    def _screen_update(self, args, cancel):
        return self._screen_capture(args, cancel)

    def _input_move(self, args, cancel):
        pyautogui = self._module("pyautogui")
        x = self._number(args, "x", minimum=0, maximum=32767)
        y = self._number(args, "y", minimum=0, maximum=32767)
        pyautogui.moveTo(x, y)
        return {"x": x, "y": y}

    def _input_click(self, args, cancel):
        pyautogui = self._module("pyautogui")
        button = args.get("button", "left")
        if button not in ("left", "right", "middle"):
            raise ValueError("invalid mouse button")
        clicks = int(self._number(args, "clicks", minimum=1, maximum=3, default=1))
        pyautogui.click(button=button, clicks=clicks)
        return {"button": button, "clicks": clicks}

    def _input_scroll(self, args, cancel):
        amount = int(self._number(args, "amount", minimum=-100, maximum=100))
        self._module("pyautogui").scroll(amount)
        return {"amount": amount}

    def _input_type(self, args, cancel):
        text = self._string(args, "text", limit=4096, required=False)
        self._module("pyautogui").write(text, interval=0.001)
        return {"characters": len(text)}

    def _input_hotkey(self, args, cancel):
        keys = args.get("keys")
        allowed = {"ctrl", "alt", "shift", "win", "enter", "esc", "tab", "space", "backspace",
                   "delete", "up", "down", "left", "right", "home", "end", "pageup", "pagedown"}
        allowed.update(chr(code) for code in range(ord("a"), ord("z") + 1))
        allowed.update(str(n) for n in range(10))
        allowed.update(f"f{n}" for n in range(1, 13))
        if not isinstance(keys, list) or not 1 <= len(keys) <= 4 or any(k not in allowed for k in keys):
            raise ValueError("keys must contain 1 to 4 supported key names")
        self._module("pyautogui").hotkey(*keys)
        return {"keys": keys}

    def _windows(self):
        return self._module("pygetwindow")

    def _window_list(self, args, cancel):
        windows = self._windows().getAllWindows()
        return {"windows": [{"title": w.title, "active": bool(w.isActive)} for w in windows if w.title][:100]}

    def _window_target(self, args):
        title = self._string(args, "title", limit=512)
        matches = [w for w in self._windows().getAllWindows() if w.title == title]
        if len(matches) != 1:
            raise ValueError("window title must identify exactly one window")
        return matches[0]

    def _window_activate(self, args, cancel):
        window = self._window_target(args)
        window.activate()
        return {"title": window.title}

    def _window_close(self, args, cancel):
        window = self._window_target(args)
        window.close()
        return {"title": window.title}

    def _app_launch(self, args, cancel):
        self._module("os")
        if "path" in args:
            path = self._path(self._string(args, "path"), must_exist=True)
            if not path.is_file() or path.suffix.lower() not in {".exe", ".lnk"}:
                raise ValueError("app path must be an .exe or .lnk file within an allowed root")
        else:
            app = self._string({"app": args.get("app", args.get("name"))}, "app", limit=256)
            if any(char in app for char in "\\/:") or app in (".", ".."):
                raise ValueError("app must be a Start Menu shortcut name")
            start_menus = [Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
                           Path(os.environ.get("PROGRAMDATA", "")) / "Microsoft/Windows/Start Menu/Programs"]
            matches = []
            for root in start_menus:
                if root.is_dir():
                    matches.extend(shortcut for shortcut in root.rglob("*.lnk")
                                   if shortcut.stem.casefold() == app.casefold())
            if len(matches) != 1:
                raise ValueError("app name must match exactly one installed Start Menu shortcut")
            path = matches[0]
        os.startfile(str(path))
        return {"path": str(path)}

    def _project_open(self, args, cancel):
        self._module("os")
        path = self._path(self._string(args, "path"), must_exist=True)
        if not path.is_dir():
            raise ValueError("project path must be a directory")
        os.startfile(str(path))
        return {"path": str(path)}

    def _app_close(self, args, cancel):
        psutil = self._module("psutil")
        pid = int(self._number(args, "pid", minimum=1, maximum=2**31 - 1))
        process = psutil.Process(pid)
        process.terminate()
        try:
            process.wait(timeout=5)
        except psutil.TimeoutExpired:
            raise RuntimeError("app did not close within 5 seconds")
        return {"pid": pid}

    def _system_status(self, args, cancel):
        data = {"platform": self.platform, "capabilities": self.capabilities()}
        if sys.platform == "win32":
            try:
                psutil = self._module("psutil")
                data.update(cpu_percent=psutil.cpu_percent(interval=None),
                            memory_percent=psutil.virtual_memory().percent)
            except Unavailable:
                pass
        return data

    def _system_apps(self, args, cancel):
        psutil = self._module("psutil")
        apps = []
        for process in psutil.process_iter(["pid", "name"]):
            try:
                apps.append({"pid": process.info["pid"], "name": process.info["name"]})
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
            if len(apps) >= 200:
                break
        return {"apps": apps}

    @staticmethod
    def _url(args):
        url = WindowsAdapter._string(args, "url", limit=2048)
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
            raise ValueError("url must be an HTTP(S) URL without credentials")
        return url

    def _browser_run(self, operation, args, cancel):
        if operation in ("open", "navigate"):
            self._url(args)
        elif operation in ("click", "type"):
            self._string(args, "selector", limit=512)
            if operation == "type":
                self._string(args, "text", limit=4096, required=False)
        self._module("playwright.sync_api")
        with self._browser_lock:
            if self._browser_worker is None:
                self._browser_worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="jarvis-browser")
            future = self._browser_worker.submit(self._browser_action, operation, args)
        while True:
            try:
                return future.result(timeout=0.1)
            except FutureTimeout:
                if cancel.is_set():
                    raise RuntimeError("cancelled; browser operation may still be finishing")

    def _browser_action(self, operation, args):
        if "page" not in self._browser_state:
            sync_api = importlib.import_module("playwright.sync_api")
            runtime = sync_api.sync_playwright().start()
            try:
                browser = runtime.chromium.launch(headless=False)
                context = browser.new_context()
                page = context.new_page()
            except BaseException:
                runtime.stop()
                raise
            self._browser_state.update(runtime=runtime, browser=browser, context=context, page=page)
        page = self._browser_state["page"]
        if operation in ("open", "navigate"):
            page.goto(self._url(args), timeout=15000, wait_until="domcontentloaded")
        elif operation in ("click", "type"):
            selector = self._string(args, "selector", limit=512)
            locator = page.locator(selector)
            if locator.count() != 1:
                raise ValueError("selector must match exactly one element")
            if operation == "click":
                locator.click(timeout=10000)
            else:
                locator.fill(self._string(args, "text", limit=4096, required=False), timeout=10000)
        elif operation == "extract":
            selector = self._string(args, "selector", limit=512, required=False)
            locator = page.locator(selector) if selector else page.locator("body")
            return {"url": page.url, "text": locator.inner_text(timeout=10000)[:65536]}
        elif operation == "back":
            page.go_back(timeout=15000, wait_until="domcontentloaded")
        elif operation == "forward":
            page.go_forward(timeout=15000, wait_until="domcontentloaded")
        return {"url": page.url, "title": page.title()}

    def _browser_open(self, args, cancel):
        return self._browser_run("open", args, cancel)

    def _browser_navigate(self, args, cancel):
        return self._browser_run("navigate", args, cancel)

    def _browser_click(self, args, cancel):
        return self._browser_run("click", args, cancel)

    def _browser_type(self, args, cancel):
        return self._browser_run("type", args, cancel)

    def _browser_extract(self, args, cancel):
        return self._browser_run("extract", args, cancel)

    def _browser_back(self, args, cancel):
        return self._browser_run("back", args, cancel)

    def _browser_forward(self, args, cancel):
        return self._browser_run("forward", args, cancel)

    def close(self):
        """Release the isolated browser; call during companion shutdown."""
        with self._browser_lock:
            worker = self._browser_worker
            self._browser_worker = None
        if worker:
            def stop():
                state = self._browser_state
                if state:
                    state["context"].close()
                    state["browser"].close()
                    state["runtime"].stop()
                    state.clear()
            worker.submit(stop).result(timeout=10)
            worker.shutdown(wait=True)
