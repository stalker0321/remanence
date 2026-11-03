"""SMS Gateway for Android adapter (sms-gate.app), for testing until BulkGate is
connected: the owner's phone sends the SMS from its own SIM. The provider only
carries the message: the server generates and checks the code.

With a passphrase, number and text are encrypted for the phone before they
leave this server, so the relay cloud sees neither. Number and code never reach
logs."""

from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from remanence.pending.providers import ProviderError, SmsSender

CLOUD_API = "https://api.sms-gate.app/3rdparty/v1"
TIMEOUT_SECONDS = 15
# Matches the 10 minute attempt; a code delivered later is useless.
TTL_SECONDS = 600
# The phone derives the same key per message; this stays quick on older phones.
PBKDF2_ITERATIONS = 150_000


def encrypt_for_device(passphrase: str, cleartext: str, *, salt: bytes | None = None) -> str:
    """The app's passphrase format: PBKDF2-SHA1 key, AES-256-CBC with the salt as IV."""
    salt = os.urandom(16) if salt is None else salt
    key = PBKDF2HMAC(algorithm=hashes.SHA1(), length=32, salt=salt,
                     iterations=PBKDF2_ITERATIONS).derive(passphrase.encode("utf-8"))
    padder = padding.PKCS7(128).padder()
    data = padder.update(cleartext.encode("utf-8")) + padder.finalize()
    encryptor = Cipher(algorithms.AES(key), modes.CBC(salt)).encryptor()
    sealed = encryptor.update(data) + encryptor.finalize()
    return (f"$aes-256-cbc/pbkdf2-sha1$i={PBKDF2_ITERATIONS}"
            f"${base64.b64encode(salt).decode()}${base64.b64encode(sealed).decode()}")


class SmsGateSender(SmsSender):
    def __init__(self, *, username: str, password: str, passphrase: str | None = None,
                 api: str = CLOUD_API) -> None:
        if not username or not password or not api.startswith("https://"):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        self._username = username
        self._password = password
        self._passphrase = passphrase
        self._api = api.rstrip("/")

    def __repr__(self) -> str:
        return "SmsGateSender(<redacted>)"

    def _body(self, to_e164: str, text: str) -> dict:
        body = {"textMessage": {"text": text}, "phoneNumbers": [to_e164], "ttl": TTL_SECONDS}
        if self._passphrase:
            body = {"textMessage": {"text": encrypt_for_device(self._passphrase, text)},
                    "phoneNumbers": [encrypt_for_device(self._passphrase, to_e164)],
                    "ttl": TTL_SECONDS, "isEncrypted": True}
        return body

    def send(self, *, attempt_id: str, to_e164: str, text: str) -> str:
        credentials = base64.b64encode(f"{self._username}:{self._password}".encode()).decode("ascii")
        request = urllib.request.Request(
            f"{self._api}/messages",
            data=json.dumps(self._body(to_e164, text)).encode("utf-8"),
            headers={"Authorization": f"Basic {credentials}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                payload = json.loads(response.read())
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            # The HTTP error body can echo the number, so it is never surfaced.
            raise ProviderError("PROVIDER_UNAVAILABLE") from exc
        if not isinstance(payload, dict) or not payload.get("id") or payload.get("state") == "Failed":
            raise ProviderError("PROVIDER_UNAVAILABLE")
        return str(payload["id"])[:128]
