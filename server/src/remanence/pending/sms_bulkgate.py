"""BulkGate transactional SMS adapter (ADR-020). The provider only carries the
message: the server generates and checks the code. Number and code never reach logs."""

from __future__ import annotations

import json
import urllib.error
import urllib.request

from remanence.pending.providers import ProviderError, SmsSender

ENDPOINT = "https://portal.bulkgate.com/api/1.0/simple/transactional"
TIMEOUT_SECONDS = 15


class BulkGateSmsSender(SmsSender):
    def __init__(self, *, application_id: str, application_token: str, sender_name: str | None = None) -> None:
        if not application_id or not application_token:
            raise ProviderError("PROVIDER_UNAVAILABLE")
        self._application_id = application_id
        self._application_token = application_token
        self._sender_name = sender_name

    def __repr__(self) -> str:
        return "BulkGateSmsSender(<redacted>)"

    def _body(self, to_e164: str, text: str) -> dict:
        body = {
            "application_id": self._application_id,
            "application_token": self._application_token,
            "number": to_e164.removeprefix("+"),
            "text": text,
            "unicode": False,
            "duplicates_check": "off",
        }
        if self._sender_name:
            body["sender_id"] = "gText"
            body["sender_id_value"] = self._sender_name
        return body

    def send(self, *, attempt_id: str, to_e164: str, text: str) -> str:
        request = urllib.request.Request(
            ENDPOINT,
            data=json.dumps(self._body(to_e164, text)).encode("utf-8"),
            headers={"Content-Type": "application/json", "Cache-Control": "no-cache"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                payload = json.loads(response.read())
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            # The HTTP error body can echo the number, so it is never surfaced.
            raise ProviderError("PROVIDER_UNAVAILABLE") from exc
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict) or data.get("status") not in ("accepted", "sent", "scheduled"):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        return str(data.get("sms_id") or attempt_id)[:128]
