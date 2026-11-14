import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from companion.windows import WindowsAdapter


class WindowsAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "allowed"
        self.root.mkdir()
        self.adapter = WindowsAdapter([self.root])
        self.cancel = threading.Event()

    def run_action(self, action, **args):
        return self.adapter.execute(action, args, self.cancel)

    def test_rejects_unknown_action_and_non_object_args(self):
        self.assertFalse(self.run_action("shell.run", command="whoami")["ok"])
        self.assertFalse(self.adapter.execute("file.read", [], self.cancel)["ok"])

    def test_file_create_read_edit_and_summarize(self):
        path = str(self.root / "note.txt")
        self.assertTrue(self.run_action("file.create", path=path, content="hello\nworld")["ok"])
        self.assertEqual(self.run_action("file.read", path=path)["data"]["content"], "hello\nworld")
        self.assertTrue(self.run_action("file.edit", path=path, old="world", new="Windows")["ok"])
        summary = self.run_action("file.summarize", path=path)["data"]
        self.assertEqual(summary["lines"], 2)
        self.assertIn("Windows", summary["preview"])
        self.assertFalse(self.run_action("file.create", path=path, content="overwrite")["ok"])

    def test_outside_and_symlink_escape_are_rejected(self):
        outside = Path(self.temp.name) / "secret.txt"
        outside.write_text("secret")
        self.assertIn("outside allowed roots", self.run_action("file.read", path=str(outside))["error"])
        link = self.root / "link.txt"
        link.symlink_to(outside)
        self.assertIn("outside allowed roots", self.run_action("file.read", path=str(link))["error"])
        self.assertIn("outside allowed roots", self.run_action("file.create", path=str(link))["error"])

    def test_move_and_organize_scope_and_collision(self):
        source = self.root / "a.txt"
        source.write_text("a")
        destination = self.root / "b.txt"
        result = self.run_action("file.move", source=str(source), destination=str(destination))
        self.assertTrue(result["ok"])
        self.assertFalse(source.exists())
        self.assertTrue(destination.exists())
        outside = Path(self.temp.name) / "outside.txt"
        result = self.run_action("file.organize", moves=[{"source": str(destination), "destination": str(outside)}])
        self.assertFalse(result["ok"])
        self.assertTrue(destination.exists())

    def test_search_limits_to_allowed_tree(self):
        (self.root / "report.txt").write_text("inside")
        outside = Path(self.temp.name) / "outside"
        outside.mkdir()
        (outside / "report-secret.txt").write_text("outside")
        (self.root / "escape").symlink_to(outside, target_is_directory=True)
        result = self.run_action("file.search", root=str(self.root), query="report")
        self.assertEqual(result["data"]["matches"], [str((self.root / "report.txt").resolve())])

    def test_cancellation_prevents_effect(self):
        self.cancel.set()
        path = self.root / "cancel.txt"
        self.assertFalse(self.run_action("file.create", path=str(path), content="x")["ok"])
        self.assertFalse(path.exists())

    def test_windows_calls_can_be_mocked_on_macos(self):
        fake = MagicMock()
        window = MagicMock(title="Editor", isActive=False)
        fake.getAllWindows.return_value = [window]
        with patch("companion.windows.sys.platform", "win32"), patch(
            "companion.windows.importlib.import_module", return_value=fake
        ):
            self.assertTrue(self.run_action("window.activate", title="Editor")["ok"])
            window.activate.assert_called_once()
            self.assertFalse(self.run_action("window.close", title="Missing")["ok"])

    def test_launch_installed_app_by_exact_shortcut_name(self):
        appdata = Path(self.temp.name) / "appdata"
        shortcut_dir = appdata / "Microsoft/Windows/Start Menu/Programs"
        shortcut_dir.mkdir(parents=True)
        shortcut = shortcut_dir / "Chrome.lnk"
        shortcut.write_bytes(b"shortcut")
        with patch("companion.windows.sys.platform", "win32"), patch.dict(
            "companion.windows.os.environ", {"APPDATA": str(appdata), "PROGRAMDATA": str(self.root)}
        ), patch("companion.windows.os.startfile", create=True) as start:
            self.assertTrue(self.run_action("app.launch", app="chrome")["ok"])
            start.assert_called_once_with(str(shortcut))
            self.assertFalse(self.run_action("app.launch", app="../Chrome")["ok"])

    def test_missing_dependency_is_explicit(self):
        with patch("companion.windows.sys.platform", "win32"), patch(
            "companion.windows.importlib.import_module", side_effect=ImportError("missing")
        ):
            result = self.run_action("screen.capture")
        self.assertFalse(result["ok"])
        self.assertIn("optional dependency", result["error"])

    def test_browser_rejects_non_http_urls(self):
        for url in ("file:///etc/passwd", "javascript:alert(1)", "https://user:pass@example.com"):
            with self.assertRaises(ValueError):
                self.adapter._url({"url": url})


if __name__ == "__main__":
    unittest.main()
