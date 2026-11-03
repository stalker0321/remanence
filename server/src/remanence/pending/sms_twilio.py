"""Twilio Messages API adapter (ADR-020), for testing until BulkGate is connected.
The provider only carries the message: the server generates and checks the code.
Number and code never reach logs."""

from __future__ import annotations

import base64
import json
import urllib.error
import urllib.parse
import urllib.request

from remanence.pending.providers import ProviderError, SmsSender

API = "https://api.twilio.com/2010-04-01/Accounts"
TIMEOUT_SECONDS = 15


class TwilioSmsSender(SmsSender):
    def __init__(self, *, account_sid: str, auth_token: str, sender: str) -> None:
        if not account_sid.startswith("AC") or not auth_token or not sender:
            raise ProviderError("PROVIDER_UNAVAILABLE")
        self._account_sid = account_sid
        self._auth_token = auth_token
        self._sender = sender

    def __repr__(self) -> str:
        return "TwilioSmsSender(<redacted>)"

    @property
    def endpoint(self) -> str:
        return f"{API}/{urllib.parse.quote(self._account_sid, safe='')}/Messages.json"

    def send(self, *, attempt_id: str, to_e164: str, text: str) -> str:
        credentials = base64.b64encode(f"{self._account_sid}:{self._auth_token}".encode()).decode("ascii")
        request = urllib.request.Request(
            self.endpoint,
            data=urllib.parse.urlencode({"To": to_e164, "From": self._sender, "Body": text}).encode("ascii"),
            headers={"Authorization": f"Basic {credentials}",
                     "Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                payload = json.loads(response.read())
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            # The HTTP error body can echo the number, so it is never surfaced.
            raise ProviderError("PROVIDER_UNAVAILABLE") from exc
        if not isinstance(payload, dict) or payload.get("status") not in ("accepted", "queued", "sending", "sent"):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        return str(payload.get("sid") or attempt_id)[:128]
