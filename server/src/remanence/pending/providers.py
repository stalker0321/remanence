"""Address-proof dispatch boundary (T185 M2/M3). No real sends here.

The server owns gift/account/session binding, consent, retries and
budgets; providers only move bytes. Two frozen transport semantics:

- EMAIL: server-generated OTP, delivered by a transactional sender. The
  sender receives the code (it must put it in the message); verification
  is a local HMAC compare. No fake success: undelivered mail simply
  never verifies.
- SMS: provider-owned Verify flow. The provider generates and checks the
  code; the server NEVER sees it. check() MUST consult the provider —
  a local compare would be fake success. Unconfigured provider fails
  closed before any budget is consumed.

Production wiring (Twilio Verify / Resend) is an owner deployment
choice; this module offers the injectable seams plus fail-closed
defaults and in-memory mocks for isolated tests.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone


class ProviderError(RuntimeError):
    def __init__(self, code: str = "PROVIDER_UNAVAILABLE") -> None:
        super().__init__("provider unavailable")
        self.code = code


@dataclass(frozen=True)
class DispatchRecord:
    attempt_id: str
    destination_kind: str
    # Stored destination is NEVER the caller-supplied string; the service
    # passes the committed value. Records keep lengths only, not values.
    destination_length: int
    dispatched_at: datetime


class TransactionalEmailSender:
    """Sends a server-generated OTP to the STORED destination."""

    def send(
        self, *, attempt_id: str, stored_destination: str, code: str, locale: str
    ) -> str:
        raise NotImplementedError


class SmsVerifyProvider:
    """Provider-owned code lifecycle: start returns a reference, check
    verifies the user-supplied code against provider state."""

    def start(self, *, attempt_id: str, stored_destination: str, locale: str) -> str:
        raise NotImplementedError

    def check(self, *, attempt_id: str, provider_reference: str, code: str) -> str:
        """Return exactly 'approved' | 'rejected' | 'expired'."""
        raise NotImplementedError


class FailClosedEmailSender(TransactionalEmailSender):
    def send(self, *, attempt_id: str, stored_destination: str, code: str, locale: str) -> str:
        raise ProviderError("PROVIDER_UNAVAILABLE")


class FailClosedSmsVerify(SmsVerifyProvider):
    def start(self, *, attempt_id: str, stored_destination: str, locale: str) -> str:
        raise ProviderError("PROVIDER_UNAVAILABLE")

    def check(self, *, attempt_id: str, provider_reference: str, code: str) -> str:
        raise ProviderError("PROVIDER_UNAVAILABLE")


class MockEmailSender(TransactionalEmailSender):
    """Isolated-test inbox: records (reference, code) so tests read the
    code the way a mailbox would. Mode-gated: prod/beta refused."""

    def __init__(self, *, mode: str = "test") -> None:
        if mode in ("prod", "beta"):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        self.inbox: dict[str, str] = {}
        self.dispatched: list[DispatchRecord] = []

    def send(self, *, attempt_id: str, stored_destination: str, code: str, locale: str) -> str:
        reference = f"mock-email-{attempt_id}"
        self.inbox[reference] = code
        self.dispatched.append(
            DispatchRecord(
                attempt_id=attempt_id,
                destination_kind="stored",
                destination_length=len(stored_destination),
                dispatched_at=datetime.now(timezone.utc),
            )
        )
        return reference


class FileSinkEmailSender(TransactionalEmailSender):
    """Dev-bootstrap OTP sink: appends one JSON line per dispatch to a
    root-readable local file, so an HTTP proof run can obtain the OTP
    without a mailbox. No network, no real sends, and NO HTTP reader
    endpoint (the file is the only reader). Mode-gated: prod/beta
    refused. Parent directory must already exist (fail closed); the
    file itself is created 0600 if absent. The stored destination is
    never written — only the attempt reference, code, locale, and
    timestamp (the R8 response already gives the caller its
    attempt_id)."""

    def __init__(self, *, path: str, mode: str = "test") -> None:
        import os as _os

        if mode in ("prod", "beta"):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        if not path or not isinstance(path, str):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        parent = _os.path.dirname(_os.path.abspath(path))
        if not _os.path.isdir(parent):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        self._path = path
        self.dispatched: list[DispatchRecord] = []

    @property
    def path(self) -> str:
        return self._path

    def send(self, *, attempt_id: str, stored_destination: str, code: str, locale: str) -> str:
        import json as _json
        import os as _os

        reference = f"dev-email-{attempt_id}"
        record = {
            "reference": reference,
            "code": code,
            "locale": locale,
            "dispatched_at": datetime.now(timezone.utc).isoformat(),
        }
        fd = _os.open(self._path, _os.O_APPEND | _os.O_CREAT | _os.O_WRONLY, 0o600)
        try:
            _os.write(fd, (_json.dumps(record) + "\n").encode("utf-8"))
        finally:
            _os.close(fd)
        self.dispatched.append(
            DispatchRecord(
                attempt_id=attempt_id,
                destination_kind="stored",
                destination_length=len(stored_destination),
                dispatched_at=datetime.now(timezone.utc),
            )
        )
        return reference


class MockSmsVerify(SmsVerifyProvider):
    """Isolated-test Verify flow: the mock owns code lifecycle. Tests
    fetch the issued code from issued_codes (the handset side), never
    from server state. Approves only its own issued codes. Mode-gated."""

    def __init__(self, *, mode: str = "test") -> None:
        if mode in ("prod", "beta"):
            raise ProviderError("PROVIDER_UNAVAILABLE")
        self.issued_codes: dict[str, str] = {}
        self.dispatched: list[DispatchRecord] = []

    def start(self, *, attempt_id: str, stored_destination: str, locale: str) -> str:
        import secrets as _secrets

        reference = f"mock-sms-{attempt_id}"
        self.issued_codes[reference] = "".join(
            _secrets.choice("0123456789") for _ in range(6)
        )
        self.dispatched.append(
            DispatchRecord(
                attempt_id=attempt_id,
                destination_kind="stored",
                destination_length=len(stored_destination),
                dispatched_at=datetime.now(timezone.utc),
            )
        )
        return reference

    def check(self, *, attempt_id: str, provider_reference: str, code: str) -> str:
        import hmac as _hmac

        expected = self.issued_codes.get(provider_reference)
        if expected is None:
            return "expired"
        if _hmac.compare_digest(code, expected):
            return "approved"
        return "rejected"


# Backwards-compatible mock name for the retired unified mock. Email
# semantics: the SERVER-generated code passes through to the inbox (the
# mock never substitutes its own code — that would desync the stored
# HMAC). `approval_code` is retained for call compatibility and ignored.
class MockDispatcher(MockEmailSender):
    def __init__(self, *, approval_code: str = "12345678", mode: str = "test") -> None:
        super().__init__(mode=mode)
        self._approval_code = approval_code

    def check(self, *, attempt_id: str, provider_reference: str, code: str) -> str:
        import hmac as _hmac

        expected = self.inbox.get(provider_reference)
        if expected is None:
            return "expired"
        return "approved" if _hmac.compare_digest(code, expected) else "rejected"
        import hmac as _hmac

        if _hmac.compare_digest(code, self._approval_code):
            return "approved"
        return "rejected"
