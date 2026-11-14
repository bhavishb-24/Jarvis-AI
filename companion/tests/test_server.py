import json
import base64
import ssl
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from companion.journal import Journal
from companion.server import CompanionServer


class FakeAdapter:
    platform = "test"

    def __init__(self):
        self.calls = []
        self.first_started = threading.Event()
        self.release_first = threading.Event()

    def capabilities(self):
        return ["system.status", "input.click", "screen.capture"]

    def execute(self, action, args, cancel):
        self.calls.append(action)
        if args.get("block"):
            self.first_started.set()
            self.release_first.wait(3)
        if action == "screen.capture":
            return {"ok": True, "data": {"mime_type": "image/png",
                                          "base64": base64.b64encode(b"PNG-example").decode()}}
        return {"ok": True, "data": {"action": action}}


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.adapter = FakeAdapter()
        self.server = CompanionServer(self.adapter, self.temp.name).start()
        self.context = ssl._create_unverified_context()
        self.token = None

    def tearDown(self):
        self.adapter.release_first.set()
        self.server.stop()
        self.temp.cleanup()

    def request(self, method, path, body=None, token=None):
        headers = {}
        if body is not None:
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.server.address + path, data=body,
                                     headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, context=self.context, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        data = response.read()
        return response.status, json.loads(data) if response.headers.get_content_type() == "application/json" else data

    def pair(self):
        code, result = self.request("POST", "/v1/pair", {"code": self.server.pairing_code})
        self.assertEqual(code, 200)
        self.token = result["token"]
        return result

    def wait_task(self, task_id, status):
        deadline = time.time() + 3
        while time.time() < deadline:
            _, task = self.request("GET", "/v1/tasks/" + task_id, token=self.token)
            if task["status"] == status:
                return task
            time.sleep(0.02)
        self.fail("task did not reach " + status)

    def test_auth_pairing_and_revoke(self):
        self.assertEqual(self.request("GET", "/v1/status")[0], 401)
        self.assertEqual(self.request("POST", "/v1/pair", {"code": "wrong"})[0], 403)
        paired = self.pair()
        self.assertEqual(self.request("POST", "/v1/pair", {"code": "wrong"})[0], 403)
        code, status = self.request("GET", "/v1/status", token=self.token)
        self.assertEqual((code, status["platform"], status["status"]), (200, "test", "online"))
        self.assertIn("system.status", status["capabilities"])
        self.assertEqual(self.request("DELETE", "/v1/devices/" + paired["device_id"], token=self.token)[0], 200)
        self.assertEqual(self.request("GET", "/v1/status", token=self.token)[0], 401)

    def test_rate_limit(self):
        for _ in range(5):
            self.request("POST", "/v1/pair", {"code": "wrong"})
        self.assertEqual(self.request("POST", "/v1/pair", {"code": self.server.pairing_code})[0], 429)

    def test_approval_idempotency_and_checkpoints(self):
        self.pair()
        body = {"task_id": "stable-1", "steps": [{"action": "input.click", "args": {}}]}
        self.assertEqual(self.request("POST", "/v1/tasks", body, self.token)[0], 403)
        self.assertEqual(self.adapter.calls, [])
        body["steps"][0]["approved"] = True
        self.assertEqual(self.request("POST", "/v1/tasks", body, self.token)[0], 202)
        task = self.wait_task("stable-1", "completed")
        self.assertEqual(task["progress"], {"completed": 1, "total": 1})
        self.assertEqual(self.request("POST", "/v1/tasks", body, self.token)[0], 200)
        self.assertEqual(self.adapter.calls, ["input.click"])

    def test_cancel_prevents_later_steps(self):
        self.pair()
        body = {"task_id": "cancel-me", "steps": [
            {"action": "system.status", "args": {"block": True}},
            {"action": "system.status", "args": {}}]}
        self.assertEqual(self.request("POST", "/v1/tasks", body, self.token)[0], 202)
        self.assertTrue(self.adapter.first_started.wait(2))
        self.assertEqual(self.request("POST", "/v1/tasks/cancel-me/cancel", token=self.token)[0], 200)
        self.adapter.release_first.set()
        task = self.wait_task("cancel-me", "cancelled")
        self.assertEqual(task["progress"]["completed"], 1)
        self.assertEqual(self.adapter.calls, ["system.status"])

    def test_restart_marks_running_task_interrupted(self):
        path = self.server.data_dir / "journal.sqlite3"
        task, created = self.server.journal.create_task("recover-me", "device", [{"action": "system.status", "args": {}, "approved": False}], False)
        self.assertTrue(created)
        self.server.journal.set_status("recover-me", "running")
        recovered = Journal(path)
        self.assertEqual(recovered.get_task("recover-me")["status"], "interrupted")
        recovered.close()

    def test_screenshot_is_authenticated_artifact(self):
        self.pair()
        body = {"task_id": "screen-1", "steps": [{"action": "screen.capture", "args": {}}]}
        self.assertEqual(self.request("POST", "/v1/tasks", body, self.token)[0], 202)
        task = self.wait_task("screen-1", "completed")
        data = task["results"][0]["data"]
        self.assertNotIn("base64", data)
        artifact_path = "/v1/artifacts/" + data["artifact_id"]
        self.assertEqual(self.request("GET", artifact_path)[0], 401)
        self.assertEqual(self.request("GET", artifact_path, token=self.token), (200, b"PNG-example"))


if __name__ == "__main__":
    unittest.main()
