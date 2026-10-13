"""Common locator persistence (T185 M2). DB UNIQUE is the authority.

- Allocate under the global code-HMAC UNIQUE constraint, <=32 candidate
  attempts; IntegrityError collision -> internal retry; exhaustion ->
  LOCATOR_ALLOCATION_BUSY. Every EXPOSED code leaves a tombstone; exposed
  codes are never reused (tombstones included in the existence check).
- States RESERVED -> ACTIVE -> RETIRED_OPEN / RETIRED_TERMINAL. Lookup,
  proof, claim, ACK, downloads, failed scans, admission/grant alone and
  Unsupported UI never retire (enforced by offering ONLY these explicit
  transitions; M3 claim/completion call retire_open).
- Capacity: issuance refuses past the configured cap WITHOUT impairing
  existing owner access (checked before insert, not as a delete).
"""

from __future__ import annotations

import hashlib
import hmac
import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from remanence.pending.locator import MAX_ALLOCATION_ATTEMPTS, generate_candidate
from remanence.pending.models import GiftLocator, UsedLocatorTombstone

# 10% of the 32^5 space; recommendation, not owner-approved product policy.
DEFAULT_ISSUANCE_CAP = 3_355_443


class LocatorStoreError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("locator store failed")
        self.code = code


def code_hmac(canonical_code: str, hmac_key: bytes) -> bytes:
    if not hmac_key or len(hmac_key) < 32:
        raise LocatorStoreError("LOCATOR_ALLOCATION_BUSY")
    return hmac.new(hmac_key, f"locator-v1|{canonical_code}".encode("ascii"), hashlib.sha256).digest()


def _code_known(session: Session, tag: bytes) -> bool:
    return (
        session.scalar(select(GiftLocator.id).where(GiftLocator.code_hmac == tag)) is not None
        or session.scalar(
            select(UsedLocatorTombstone.code_hmac).where(UsedLocatorTombstone.code_hmac == tag)
        )
        is not None
    )


def allocate_reserved(
    session: Session,
    *,
    capsule_id: uuid.UUID,
    hint_mask: str,
    hmac_key: bytes,
    issuance_cap: int = DEFAULT_ISSUANCE_CAP,
    issued_count: int = 0,
) -> tuple[GiftLocator, str]:
    """Reserve a fresh code for a capsule. Returns (row, canonical_code).

    The plaintext code is returned ONCE for sender-side encryption of the
    printable code; only HMAC + ciphertext persist.
    """
    if issued_count >= issuance_cap:
        raise LocatorStoreError("LOCATOR_CAPACITY_REACHED")
    existing = session.scalar(select(GiftLocator).where(GiftLocator.capsule_id == capsule_id))
    if existing is not None:
        raise LocatorStoreError("LOCATOR_ALLOCATION_BUSY")
    last_error: Exception | None = None
    for _ in range(MAX_ALLOCATION_ATTEMPTS):
        candidate = generate_candidate()
        tag = code_hmac(candidate, hmac_key)
        if _code_known(session, tag):
            continue
        row = GiftLocator(
            id=uuid.uuid4(),
            capsule_id=capsule_id,
            code_hmac=tag,
            code_ciphertext=None,
            state="RESERVED",
            hint_mask=hint_mask,
            policy_version=1,
        )
        session.add(row)
        try:
            session.flush()
        except IntegrityError as exc:
            session.rollback()
            last_error = exc
            continue
        return row, candidate
    raise LocatorStoreError("LOCATOR_ALLOCATION_BUSY") from last_error


def activate(session: Session, *, locator_id: uuid.UUID, code_ciphertext: bytes) -> GiftLocator:
    """RESERVED -> ACTIVE with the encrypted printable code, committed
    together with publication by the caller (same transaction)."""
    row = session.get(GiftLocator, locator_id)
    if row is None or row.state != "RESERVED":
        raise LocatorStoreError("LOCATOR_ALLOCATION_BUSY")
    row.code_ciphertext = code_ciphertext
    row.state = "ACTIVE"
    session.flush()
    return row


def retire(
    session: Session, *, locator_id: uuid.UUID, terminal: bool, now: datetime | None = None
) -> GiftLocator:
    """ACTIVE -> RETIRED_OPEN (first-open completion) or RETIRED_TERMINAL
    (cancel/expiry/deletion). Persists the HMAC tombstone; never deletes
    the recipient envelope (caller-owned concern). Idempotent replay of
    the same terminal state succeeds; cross-state replays conflict."""
    row = session.get(GiftLocator, locator_id)
    if row is None:
        raise LocatorStoreError("GIFT_UNAVAILABLE")
    target = "RETIRED_TERMINAL" if terminal else "RETIRED_OPEN"
    if row.state == target:
        return row
    if row.state != "ACTIVE":
        raise LocatorStoreError("GIFT_UNAVAILABLE")
    row.state = target
    row.retired_at = now or datetime.now(timezone.utc)
    session.add(UsedLocatorTombstone(code_hmac=row.code_hmac))
    session.flush()
    return row


def resolve_active(session: Session, *, tag: bytes) -> GiftLocator | None:
    """Lookup path: ACTIVE codes only. Unknown/retired/terminal -> None
    (caller maps to generic GIFT_UNAVAILABLE with no existence oracle)."""
    row = session.scalar(select(GiftLocator).where(GiftLocator.code_hmac == tag))
    if row is None or row.state != "ACTIVE":
        return None
    return row
