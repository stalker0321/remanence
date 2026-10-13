"""Verified-address registry (T185 M2). Transactional, no I/O beyond Session.

- `users.email_normalized` is NEVER treated as verified; backfill creates
  RESERVED rows only.
- Lookup tags are server-secret HMACs (kind/version-tagged); the secret
  is an explicit caller parameter (deployment config in prod, synthetic
  in tests) — never a global, never logged.
- Lock order (documented, tested): address-tag advisory lock → account
  row → bundle row. Other pending mutators use the same relevant order.
"""

from __future__ import annotations

import hashlib
import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.pending.models import DeliveryAddressBinding

RESERVED = "RESERVED"
VERIFIED = "VERIFIED"
RETIRED = "RETIRED"


class RegistryError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("address registry failed")
        self.code = code


def lookup_tag(kind: str, normalized: str, hmac_key: bytes) -> bytes:
    """Server-secret HMAC tag for destination lookup. Never D, never plaintext."""
    if kind not in ("email", "phone"):
        raise RegistryError("ADDRESS_UNAVAILABLE")
    if not hmac_key or len(hmac_key) < 32:
        raise RegistryError("ADDRESS_UNAVAILABLE")
    return hmac.new(
        hmac_key, f"v1|{kind}|{normalized}".encode("utf-8"), hashlib.sha256
    ).digest()


def advisory_key(kind: str, tag: bytes) -> int:
    """Signed 64-bit advisory-lock key. PostgreSQL bigint is signed: an
    unsigned conversion overflows for ~half of all digests and the lock
    call fails. Both high-bit and low-bit digests stay in range."""
    return int.from_bytes(
        hashlib.sha256(kind.encode() + tag).digest()[:8], "big", signed=True
    )


def lock_tag(session: Session, kind: str, tag: bytes) -> None:
    """Advisory lock directly on a lookup tag (for rows whose plaintext
    is not available — by design it is never stored)."""
    if session.bind is not None and session.bind.dialect.name == "postgresql":
        session.connection().exec_driver_sql(
            f"SELECT pg_advisory_xact_lock({advisory_key(kind, tag)})"
        )


def lock_address(session: Session, kind: str, normalized: str, hmac_key: bytes) -> bytes:
    """Address-tag advisory lock; returns the lookup tag.

    Lock order (documented, tested): address lock -> account row ->
    bundle row. Reserve/verify/retire/fate/freeze all take this same lock.
    On SQLite unit tests this is a documented no-op; real serialization
    is covered by isolated PG tests.
    """
    tag = lookup_tag(kind, normalized, hmac_key)
    lock_tag(session, kind, tag)
    return tag


def make_evidence_ref(
    *, kind: str, tag: bytes, method: str, owner_user_id: uuid.UUID, evidence_key: bytes
) -> bytes:
    """Authoritative evidence MAC (key outside the DB). A fabricated
    `verified=true` row without this MAC is rejected at claim time."""
    if not evidence_key or len(evidence_key) < 32:
        raise RegistryError("ADDRESS_UNAVAILABLE")
    return hmac.new(
        evidence_key,
        b"v1|bind|" + kind.encode() + b"|" + tag + b"|" + method.encode() + b"|" + owner_user_id.bytes,
        hashlib.sha256,
    ).digest()


def verify_evidence_mac(row: DeliveryAddressBinding, *, evidence_key: bytes) -> bool:
    """True iff the row's evidence MAC matches its authoritative fields."""
    import hmac as _hmac

    if row.evidence_method is None or row.evidence_ref is None:
        return False
    expected = make_evidence_ref(
        kind=row.kind,
        tag=bytes(row.lookup_tag),
        method=row.evidence_method,
        owner_user_id=row.owner_user_id,
        evidence_key=evidence_key,
    )
    return _hmac.compare_digest(bytes(row.evidence_ref), expected)


@dataclass(frozen=True)
class LiveBinding:
    owner_user_id: uuid.UUID
    revision: int


def reserve(
    session: Session,
    *,
    kind: str,
    normalized: str,
    owner_user_id: uuid.UUID,
    hmac_key: bytes,
) -> DeliveryAddressBinding:
    """Backfill-style reservation. Never promotes to VERIFIED by itself."""
    tag = lock_address(session, kind, normalized, hmac_key)
    existing = session.scalar(
        select(DeliveryAddressBinding).where(
            DeliveryAddressBinding.kind == kind,
            DeliveryAddressBinding.lookup_tag == tag,
            DeliveryAddressBinding.state != RETIRED,
        )
    )
    if existing is not None:
        if existing.owner_user_id != owner_user_id:
            raise RegistryError("ADDRESS_UNAVAILABLE")
        return existing
    row = DeliveryAddressBinding(
        id=uuid.uuid4(),
        kind=kind,
        normalization_version=1,
        lookup_tag=tag,
        owner_user_id=owner_user_id,
        state=RESERVED,
        binding_revision=1,
    )
    session.add(row)
    session.flush()
    return row


def verify_binding(
    session: Session,
    *,
    kind: str,
    normalized: str,
    owner_user_id: uuid.UUID,
    evidence_method: str,
    evidence_key: bytes,
    hmac_key: bytes,
    now: datetime,
) -> DeliveryAddressBinding:
    """Promote a reservation to VERIFIED after fresh proof (OTP/Google).

    The evidence MAC is computed server-side from authoritative fields;
    callers never supply it. Conflicting ownership never auto-merges:
    another owner's live row blocks with ADDRESS_UNAVAILABLE.
    """
    if evidence_method not in ("otp", "google"):
        raise RegistryError("ADDRESS_UNAVAILABLE")
    tag = lock_address(session, kind, normalized, hmac_key)
    row = session.scalar(
        select(DeliveryAddressBinding).where(
            DeliveryAddressBinding.kind == kind,
            DeliveryAddressBinding.lookup_tag == tag,
            DeliveryAddressBinding.state != RETIRED,
        )
    )
    if row is None:
        row = DeliveryAddressBinding(
            id=uuid.uuid4(),
            kind=kind,
            normalization_version=1,
            lookup_tag=tag,
            owner_user_id=owner_user_id,
            state=RESERVED,
            binding_revision=1,
        )
        session.add(row)
        session.flush()
    if row.owner_user_id != owner_user_id:
        raise RegistryError("ADDRESS_UNAVAILABLE")
    row.state = VERIFIED
    row.binding_revision += 1
    row.evidence_method = evidence_method
    row.evidence_ref = make_evidence_ref(
        kind=kind,
        tag=tag,
        method=evidence_method,
        owner_user_id=owner_user_id,
        evidence_key=evidence_key,
    )
    row.verified_at = now
    session.flush()
    return row


def retire_binding(
    session: Session, *, binding_id: uuid.UUID, owner_user_id: uuid.UUID
) -> None:
    """Retire under the same address lock, fenced to the owning account.
    Foreign callers get generic ADDRESS_UNAVAILABLE (no existence oracle)."""
    row = session.get(DeliveryAddressBinding, binding_id)
    if row is None or row.owner_user_id != owner_user_id:
        raise RegistryError("ADDRESS_UNAVAILABLE")
    lock_tag(session, row.kind, bytes(row.lookup_tag))
    row.state = RETIRED
    session.flush()


def address_fate(
    session: Session, *, kind: str, normalized: str, hmac_key: bytes
) -> str:
    """Classify an address under the address lock: ABSENT | RESERVED |
    VERIFIED | RETIRED. Routing treats RETIRED as an explicit error, never
    as absent->pending fallback (live_verified alone cannot see it)."""
    tag = lock_address(session, kind, normalized, hmac_key)
    row = session.scalar(
        select(DeliveryAddressBinding).where(
            DeliveryAddressBinding.kind == kind,
            DeliveryAddressBinding.lookup_tag == tag,
        )
    )
    if row is None:
        return "ABSENT"
    return row.state


def live_verified(
    session: Session, *, kind: str, normalized: str, hmac_key: bytes
) -> LiveBinding | None:
    """Authoritative VERIFIED read under the address lock. RESERVED is
    never routable; RETIRED is invisible here by design — callers that
    must distinguish retired from absent use address_fate (routing
    treats RETIRED as an explicit error, never pending fallback)."""
    tag = lock_address(session, kind, normalized, hmac_key)
    row = session.scalar(
        select(DeliveryAddressBinding).where(
            DeliveryAddressBinding.kind == kind,
            DeliveryAddressBinding.lookup_tag == tag,
            DeliveryAddressBinding.state == VERIFIED,
        )
    )
    if row is None:
        return None
    return LiveBinding(owner_user_id=row.owner_user_id, revision=row.binding_revision)
