"""SQLite checkpoints and audit history for deterministic companion tasks."""
import json
import sqlite3
import threading
import time
from pathlib import Path


class Journal:
    def __init__(self, path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        with self._lock, self._db:
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, created REAL NOT NULL)")
            self._db.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, steps TEXT NOT NULL, approved INTEGER NOT NULL, status TEXT NOT NULL, next_step INTEGER NOT NULL DEFAULT 0, results TEXT NOT NULL DEFAULT '[]', cancel_requested INTEGER NOT NULL DEFAULT 0, created REAL NOT NULL, updated REAL NOT NULL)")
            self._db.execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL NOT NULL, device_id TEXT, task_id TEXT, event TEXT NOT NULL, detail TEXT NOT NULL)")
            self._db.execute("CREATE TABLE IF NOT EXISTS artifacts (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, content_type TEXT NOT NULL, payload BLOB NOT NULL, created REAL NOT NULL)")
            self._db.execute("UPDATE tasks SET status='interrupted', updated=? WHERE status IN ('queued','running')", (time.time(),))

    def close(self):
        with self._lock:
            self._db.close()

    def audit(self, event, device_id=None, task_id=None, detail=""):
        with self._lock, self._db:
            self._db.execute("INSERT INTO audit(at,device_id,task_id,event,detail) VALUES(?,?,?,?,?)",
                             (time.time(), device_id, task_id, event, detail[:1024]))

    def add_device(self, device_id, token_hash):
        with self._lock, self._db:
            self._db.execute("INSERT INTO devices VALUES(?,?,?)", (device_id, token_hash, time.time()))

    def device_for_token(self, token_hash):
        with self._lock:
            row = self._db.execute("SELECT id FROM devices WHERE token_hash=?", (token_hash,)).fetchone()
            return row["id"] if row else None

    def revoke_device(self, device_id):
        with self._lock, self._db:
            cursor = self._db.execute("DELETE FROM devices WHERE id=?", (device_id,))
            return cursor.rowcount > 0

    def create_task(self, task_id, device_id, steps, approved):
        with self._lock, self._db:
            existing = self.get_task(task_id)
            if existing:
                return existing, False
            now = time.time()
            self._db.execute("INSERT INTO tasks(id,device_id,steps,approved,status,created,updated) VALUES(?,?,?,?,?,?,?)",
                             (task_id, device_id, json.dumps(steps), int(approved), "queued", now, now))
            return self.get_task(task_id), True

    def get_task(self, task_id):
        with self._lock:
            row = self._db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
            if not row:
                return None
            result = dict(row)
            result["steps"] = json.loads(result["steps"])
            result["results"] = json.loads(result["results"])
            result["approved"] = bool(result["approved"])
            result["cancel_requested"] = bool(result["cancel_requested"])
            return result

    def set_status(self, task_id, status):
        with self._lock, self._db:
            self._db.execute("UPDATE tasks SET status=?,updated=? WHERE id=?", (status, time.time(), task_id))

    def checkpoint(self, task_id, next_step, results, status):
        with self._lock, self._db:
            self._db.execute("UPDATE tasks SET next_step=?,results=?,status=?,updated=? WHERE id=?",
                             (next_step, json.dumps(results), status, time.time(), task_id))

    def request_cancel(self, task_id):
        with self._lock, self._db:
            self._db.execute("UPDATE tasks SET cancel_requested=1,updated=? WHERE id=?", (time.time(), task_id))

    def put_artifact(self, artifact_id, device_id, content_type, payload):
        with self._lock, self._db:
            self._db.execute("INSERT INTO artifacts VALUES(?,?,?,?,?)",
                             (artifact_id, device_id, content_type, payload, time.time()))

    def get_artifact(self, artifact_id):
        with self._lock:
            row = self._db.execute("SELECT device_id,content_type,payload FROM artifacts WHERE id=?", (artifact_id,)).fetchone()
            return dict(row) if row else None
