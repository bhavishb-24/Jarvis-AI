"""Small authenticated HTTPS executor for phone-orchestrated desktop tasks."""
import json
import base64
import re
import secrets
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .contract import requires_approval, validate_step
from .journal import Journal
from .security import PairingGate, credential_hash, tls_context, tls_identity

MAX_BODY = 256 * 1024
MAX_ARTIFACT = 8 * 1024 * 1024
TASK_ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
IDENTIFIER = re.compile(r"^[a-fA-F0-9-]{36}$")


class CompanionServer:
    """The adapter supplies platform, capabilities(), and execute(action,args,cancel)."""

    def __init__(self, adapter, data_dir, host="127.0.0.1", port=0):
        self.adapter = adapter
        self.data_dir = Path(data_dir)
        self.host, self.port = host, port
        cert, key, self.fingerprint = tls_identity(self.data_dir)
        self._tls = tls_context(cert, key)
        self.pairing = PairingGate()
        self.journal = Journal(self.data_dir / "journal.sqlite3")
        self._http = None
        self._thread = None
        self._cancels = {}
        self._workers = {}
        self._lock = threading.RLock()

    @property
    def pairing_code(self):
        return self.pairing.code

    @property
    def address(self):
        if self._http is None:
            return None
        host, port = self._http.server_address[:2]
        return "https://{}:{}".format(host, port)

    def start(self):
        if self._http is not None:
            return self
        owner = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "JARVISCompanion/1"

            def log_message(self, format, *args):
                pass

            def reply(self, code, value):
                payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(payload)

            def body(self):
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise ValueError("invalid content length")
                if length < 1 or length > MAX_BODY:
                    raise ValueError("invalid body size")
                try:
                    data = json.loads(self.rfile.read(length))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    raise ValueError("invalid JSON")
                if not isinstance(data, dict):
                    raise ValueError("body must be an object")
                return data

            def authenticate(self):
                header = self.headers.get("Authorization", "")
                if not header.startswith("Bearer "):
                    self.reply(401, {"error": "authentication required"})
                    return None
                token = header[7:]
                if not token or len(token) > 256:
                    self.reply(401, {"error": "authentication required"})
                    return None
                device = owner.journal.device_for_token(credential_hash(token))
                if device is None:
                    self.reply(401, {"error": "authentication required"})
                return device

            def do_POST(self):
                path = urlsplit(self.path).path
                if path == "/v1/pair":
                    try:
                        data = self.body()
                    except ValueError as exc:
                        self.reply(400, {"error": str(exc)})
                        return
                    accepted, limited = owner.pairing.exchange(data.get("code"))
                    if not accepted:
                        owner.journal.audit("pair_failed", detail="rate_limited" if limited else "invalid_code")
                        self.reply(429 if limited else 403, {"error": "pairing rate limited" if limited else "invalid or expired code"})
                        return
                    device_id = str(uuid.uuid4())
                    token = secrets.token_urlsafe(32)
                    owner.journal.add_device(device_id, credential_hash(token))
                    owner.journal.audit("paired", device_id)
                    self.reply(200, {"device_id": device_id, "token": token, "platform": owner.adapter.platform})
                    return
                device = self.authenticate()
                if device is None:
                    return
                if path == "/v1/tasks":
                    try:
                        data = self.body()
                        task_id = data.get("task_id")
                        if not isinstance(task_id, str) or not TASK_ID.fullmatch(task_id):
                            raise ValueError("invalid task_id")
                        existing = owner.journal.get_task(task_id)
                        if existing:
                            if existing["device_id"] != device:
                                self.reply(409, {"error": "task_id belongs to another device"})
                            else:
                                self.reply(200, owner._public_task(existing))
                            return
                        raw_steps = data.get("steps")
                        if not isinstance(raw_steps, list) or not 1 <= len(raw_steps) <= 100:
                            raise ValueError("steps must contain 1 to 100 items")
                        steps = []
                        for raw in raw_steps:
                            step = validate_step(raw)
                            approved = raw.get("approved", False)
                            if not isinstance(approved, bool):
                                raise ValueError("approved must be a boolean")
                            if requires_approval(step["action"]) and not approved:
                                raise PermissionError("approval required for " + step["action"])
                            step["approved"] = approved
                            steps.append(step)
                    except PermissionError as exc:
                        owner.journal.audit("approval_denied", device, detail=str(exc))
                        self.reply(403, {"error": str(exc)})
                        return
                    except ValueError as exc:
                        self.reply(400, {"error": str(exc)})
                        return
                    task, created = owner.journal.create_task(task_id, device, steps, any(s["approved"] for s in steps))
                    if task["device_id"] != device:
                        self.reply(409, {"error": "task_id belongs to another device"})
                        return
                    if created:
                        owner.journal.audit("task_created", device, task_id, detail="approved=" + str(task["approved"]))
                        owner._launch(task_id)
                    self.reply(202 if created else 200, owner._public_task(task))
                    return
                match = re.fullmatch(r"/v1/tasks/([^/]+)/cancel", path)
                if match:
                    task_id = match.group(1)
                    task = owner.journal.get_task(task_id)
                    if task is None or task["device_id"] != device:
                        self.reply(404, {"error": "task not found"})
                        return
                    owner.journal.request_cancel(task_id)
                    with owner._lock:
                        event = owner._cancels.get(task_id)
                        if event:
                            event.set()
                    if task["status"] in ("queued", "interrupted"):
                        owner.journal.set_status(task_id, "cancelled")
                    owner.journal.audit("task_cancel_requested", device, task_id)
                    self.reply(200, owner._public_task(owner.journal.get_task(task_id)))
                    return
                self.reply(404, {"error": "not found"})

            def do_GET(self):
                path = urlsplit(self.path).path
                device = self.authenticate()
                if device is None:
                    return
                if path == "/v1/status":
                    self.reply(200, {"status": "online", "platform": owner.adapter.platform,
                                     "capabilities": owner.adapter.capabilities(), "timestamp": time.time()})
                    return
                match = re.fullmatch(r"/v1/tasks/([^/]+)", path)
                if match:
                    task = owner.journal.get_task(match.group(1))
                    if task is None or task["device_id"] != device:
                        self.reply(404, {"error": "task not found"})
                    else:
                        self.reply(200, owner._public_task(task))
                    return
                match = re.fullmatch(r"/v1/artifacts/([^/]+)", path)
                if match:
                    artifact = owner.journal.get_artifact(match.group(1))
                    if artifact is None or artifact["device_id"] != device:
                        self.reply(404, {"error": "artifact not found"})
                        return
                    payload = artifact["payload"]
                    self.send_response(200)
                    self.send_header("Content-Type", artifact["content_type"])
                    self.send_header("Content-Length", str(len(payload)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(payload)
                    return
                self.reply(404, {"error": "not found"})

            def do_DELETE(self):
                device = self.authenticate()
                if device is None:
                    return
                match = re.fullmatch(r"/v1/devices/([^/]+)", urlsplit(self.path).path)
                if not match:
                    self.reply(404, {"error": "not found"})
                    return
                if match.group(1) != device:
                    self.reply(403, {"error": "cannot revoke another device"})
                    return
                owner.journal.audit("device_revoked", device)
                owner.journal.revoke_device(device)
                self.reply(200, {"revoked": True})

        self._http = ThreadingHTTPServer((self.host, self.port), Handler)
        self._http.daemon_threads = True
        self._http.socket = self._tls.wrap_socket(self._http.socket, server_side=True)
        self._thread = threading.Thread(target=self._http.serve_forever, daemon=True)
        self._thread.start()
        return self

    def _public_task(self, task):
        return {"task_id": task["id"], "status": task["status"], "steps": task["steps"],
                "progress": {"completed": task["next_step"], "total": len(task["steps"])},
                "results": task["results"], "cancel_requested": task["cancel_requested"]}

    def _launch(self, task_id):
        cancel = threading.Event()
        with self._lock:
            self._cancels[task_id] = cancel
            worker = threading.Thread(target=self._run_task, args=(task_id, cancel), daemon=True)
            self._workers[task_id] = worker
            worker.start()

    def _run_task(self, task_id, cancel):
        try:
            task = self.journal.get_task(task_id)
            if task is None or task["status"] != "queued":
                return
            self.journal.set_status(task_id, "running")
            results = task["results"]
            for index, step in enumerate(task["steps"]):
                current = self.journal.get_task(task_id)
                if cancel.is_set() or current["cancel_requested"]:
                    self.journal.checkpoint(task_id, index, results, "cancelled")
                    self.journal.audit("task_cancelled", task["device_id"], task_id)
                    return
                self.journal.audit("step_started", task["device_id"], task_id,
                                   "{}:{}:approved={}".format(index, step["action"], step["approved"]))
                try:
                    result = self.adapter.execute(step["action"], step["args"], cancel)
                    if not isinstance(result, dict) or not isinstance(result.get("ok"), bool):
                        raise ValueError("adapter returned an invalid result")
                    if result["ok"] and step["action"] in ("screen.capture", "screen.update"):
                        data = result.get("data") or {}
                        raw = base64.b64decode(data.pop("base64"), validate=True)
                        artifact_id = self.store_artifact(task["device_id"], raw,
                                                          data.get("mime_type", "image/png"))
                        data["artifact_id"] = artifact_id
                except Exception as exc:
                    result = {"ok": False, "error": str(exc)}
                results.append(result)
                status = "running" if result["ok"] else "failed"
                self.journal.checkpoint(task_id, index + 1, results, status)
                self.journal.audit("step_completed", task["device_id"], task_id,
                                   "{}:{}:ok={}".format(index, step["action"], result["ok"]))
                if not result["ok"]:
                    return
            self.journal.set_status(task_id, "cancelled" if cancel.is_set() else "completed")
            self.journal.audit("task_finished", task["device_id"], task_id)
        finally:
            with self._lock:
                self._cancels.pop(task_id, None)
                self._workers.pop(task_id, None)

    def store_artifact(self, device_id, payload, content_type="application/octet-stream"):
        if not isinstance(payload, bytes) or len(payload) > MAX_ARTIFACT:
            raise ValueError("artifact exceeds size cap or is not bytes")
        if not isinstance(content_type, str) or not re.fullmatch(r"[\w.+-]+/[\w.+-]+", content_type):
            raise ValueError("invalid content type")
        artifact_id = str(uuid.uuid4())
        self.journal.put_artifact(artifact_id, device_id, content_type, payload)
        return artifact_id

    def stop(self):
        if self._http is not None:
            self._http.shutdown()
            self._http.server_close()
            self._thread.join(timeout=5)
            self._http = None
        with self._lock:
            for event in self._cancels.values():
                event.set()
            workers = list(self._workers.values())
        for worker in workers:
            worker.join(timeout=5)
        self.journal.close()
