import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from companion.macos import MacAdapter


class MacAdapterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.adapter = MacAdapter([self.root])
        self.cancel = threading.Event()

    def run_action(self, action, **args):
        return self.adapter.execute(action, args, self.cancel)

    def test_contract_and_unknown_action(self):
        self.assertEqual(self.adapter.platform, "macos")
        self.assertIn("file.read", self.adapter.capabilities())
        result = self.run_action("shell.run", command="whoami")
        self.assertFalse(result["ok"])
        self.assertIn("unknown action", result["error"])

    def test_file_create_read_edit_and_search(self):
        target = self.root / "notes.txt"
        self.assertTrue(self.run_action("file.create", path=str(target), content="hello")["ok"])
        self.assertEqual(self.run_action("file.read", path=str(target))["data"]["content"], "hello")
        self.assertTrue(self.run_action("file.edit", path=str(target), content="goodbye")["ok"])
        found = self.run_action("file.search", query="notes")
        self.assertIn(str(target.resolve()), found["data"]["paths"])
        self.assertEqual(target.read_text(), "goodbye")

    def test_file_paths_stay_inside_allowed_roots(self):
        outside = self.root.parent / "outside.txt"
        self.assertFalse(self.run_action("file.create", path=str(outside), content="bad")["ok"])
        self.assertFalse(outside.exists())
        link = self.root / "link"
        link.symlink_to(self.root.parent, target_is_directory=True)
        self.assertFalse(self.run_action("file.create", path=str(link / "escape.txt"), content="bad")["ok"])

    def test_cancelled_action_has_no_effect(self):
        self.cancel.set()
        target = self.root / "cancelled.txt"
        result = self.run_action("file.create", path=str(target), content="bad")
        self.assertFalse(result["ok"])
        self.assertFalse(target.exists())

    def test_file_move_and_organize(self):
        source = self.root / "report.txt"
        source.write_text("report")
        destination = self.root / "archive" / "report.txt"
        self.assertTrue(self.run_action("file.move", source=str(source), destination=str(destination))["ok"])
        self.assertEqual(destination.read_text(), "report")
        self.assertTrue(self.run_action("file.move", source=str(destination), destination=str(source))["ok"])

    def test_organize_by_extension_without_overwriting(self):
        first = self.root / "notes.txt"
        first.write_text("one")
        existing = self.root / "txt"
        existing.mkdir()
        (existing / "notes.txt").write_text("old")
        result = self.run_action("file.organize", path=str(self.root))
        self.assertTrue(result["ok"])
        self.assertEqual(first.read_text(), "one")
        self.assertEqual((existing / "notes.txt").read_text(), "old")
        second = self.root / "photo.png"
        second.write_text("picture")
        result = self.run_action("file.organize", path=str(self.root))
        self.assertTrue(result["ok"])
        self.assertEqual((self.root / "png" / "photo.png").read_text(), "picture")

    def test_create_refuses_overwrite_and_large_content(self):
        target = self.root / "notes.txt"
        target.write_text("original")
        self.assertFalse(self.run_action("file.create", path=str(target), content="new")["ok"])
        self.assertFalse(self.run_action("file.edit", path=str(target), content="x" * (2 * 1024 * 1024 + 1))["ok"])
        self.assertEqual(target.read_text(), "original")

    def test_move_cannot_relocate_configured_root(self):
        destination = self.root / "nested" / "root"
        result = self.run_action("file.move", source=str(self.root), destination=str(destination))
        self.assertFalse(result["ok"])
        self.assertIn("configured root", result["error"])
        self.assertTrue(self.root.exists())

    def test_screen_permission_error_is_explicit(self):
        with mock.patch("companion.macos._screen_permission", return_value=False):
            result = self.run_action("screen.capture")
        self.assertFalse(result["ok"])
        self.assertIn("Screen Recording", result["error"])

    def test_input_permission_error_is_explicit(self):
        with mock.patch("companion.macos._accessibility_permission", return_value=False):
            result = self.run_action("input.click", x=10, y=10)
        self.assertFalse(result["ok"])
        self.assertIn("Accessibility", result["error"])

    def test_browser_without_playwright_is_explicit(self):
        with mock.patch("companion.macos._load_playwright", return_value=None):
            result = self.run_action("browser.open", url="https://example.com")
        self.assertFalse(result["ok"])
        self.assertIn("Playwright", result["error"])


if __name__ == "__main__":
    unittest.main()
