"""Address-proof dispatch boundary (T185 M2). No real sends here.

The server owns gift/account/session binding, consent, retries and
budgets; the provider only starts/checks a delivery attempt against the
STORED destination. Production wiring (Twilio Verify / Resend) is an
owner deployment choice; this module offers the injectable seam plus a
fail-closed default and an in-memory mock for isolated tests.
"""

from __future__ import annotations

from dataclasses import dataclass, field
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


class AddressVerificationProvider:
    """Interface. start/check return typed outcomes; never Remanence sessions."""

    def start(self, *, attempt_id: str, stored_destination: str, locale: str) -> str:
        raise NotImplementedError

    def check(self, *, attempt_id: str, provider_reference: str, code: str) -> str:
        raise NotImplementedError


class FailClosedDispatcher(AddressVerificationProvider):
    """Default: no provider configured -> explicit unavailable, never a
    fake approval."""

    def start(self, *, attempt_id: str, stored_destination: str, locale: str) -> str:
        raise ProviderError("PROVIDER_UNAVAILABLE")

    def check(self, *, attempt_id: str, provider_reference: str, code: str) -> str:
        raise ProviderError("PROVIDER_UNAVAILABLE")


class MockDispatcher(AddressVerificationProvider):
    """Injectable mock for isolated tests. Approves only the configured
    code; records dispatches without storing destinations or codes."""

    def __init__(self, *, approval_code: str = "12345678") -> None:
        self._approval_code = approval_code
        self.dispatched: list[DispatchRecord] = []
        self._references: dict[str, str] = {}

    def start(self, *, attempt_id: str, stored_destination: str, locale: str) -> str:
        reference = f"mock-{attempt_id}"
        self._references[attempt_id] = reference
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
        if self._references.get(attempt_id) != provider_reference:
            return "rejected"
        import hmac as _hmac

        if _hmac.compare_digest(code, self._approval_code):
            return "approved"
        return "rejected"
