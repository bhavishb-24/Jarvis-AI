"""Local TLS identity and pairing credentials for a companion agent."""
import hashlib
import os
import secrets
import ssl
import subprocess
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Lock


def tls_identity(directory):
    """Create or load a private self-signed certificate; return paths and fingerprint."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    key_path, cert_path = directory / "agent.key", directory / "agent.crt"
    if not key_path.exists() or not cert_path.exists():
        if key_path.exists() or cert_path.exists():
            raise RuntimeError("incomplete TLS identity")
        try:
            from cryptography import x509
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import rsa
            from cryptography.x509.oid import NameOID
        except ImportError:
            # macOS/Linux developer environments can use the system executable.
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256",
                            "-nodes", "-days", "3650", "-subj", "/CN=JARVIS companion",
                            "-keyout", str(key_path), "-out", str(cert_path)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        else:
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            now = datetime.now(timezone.utc)
            name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "JARVIS companion")])
            cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                    .public_key(key.public_key()).serial_number(x509.random_serial_number())
                    .not_valid_before(now - timedelta(minutes=1))
                    .not_valid_after(now + timedelta(days=3650))
                    .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                    .sign(key, hashes.SHA256()))
            key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
            cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        os.chmod(key_path, 0o600)
        os.chmod(cert_path, 0o600)
    cert = ssl.PEM_cert_to_DER_cert(cert_path.read_text())
    fingerprint = hashlib.sha256(cert).hexdigest().upper()
    return str(cert_path), str(key_path), fingerprint


def tls_context(cert_path, key_path):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_path, key_path)
    return context


def credential_hash(secret):
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


class PairingGate:
    def __init__(self, lifetime=600, max_attempts=5, window=60):
        self.code = secrets.token_urlsafe(12)
        self.expires_at = time.time() + lifetime
        self._attempts = deque()
        self._limit = max_attempts
        self._window = window
        self._lock = Lock()

    def exchange(self, supplied):
        """Consume code exactly once. Return (success, rate_limited)."""
        with self._lock:
            now = time.time()
            while self._attempts and self._attempts[0] <= now - self._window:
                self._attempts.popleft()
            if len(self._attempts) >= self._limit:
                return False, True
            self._attempts.append(now)
            valid = (self.code is not None and now < self.expires_at
                     and isinstance(supplied, str)
                     and secrets.compare_digest(self.code, supplied))
            if valid:
                self.code = None
            return valid, False
