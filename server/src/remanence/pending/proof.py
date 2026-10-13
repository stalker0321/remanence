"""Address-proof attempts: OTP dispatch + same-email Google shortcut (T185 M3).

Budgets: 60s resend cooldown, <=3 dispatches per attempt, <=5 attempts
per day per delivery+account, 10-minute attempt life, <=5 guesses. No
per-gift permanent lockout: expired attempts simply end; the receiver
resumes with a new ceremony. Provider failures fail closed before any
budget is consumed.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.pending.claim_models import AddressProofAttempt, PendingDelivery
from remanence.pending.providers import SmsVerifyProvider, TransactionalEmailSender
from remanence.pending.routing import open_destination

ATTEMPT_TTL = timedelta(minutes=10)
RESEND_COOLDOWN = timedelta(seconds=60)
MAX_DISPATCHES_PER_ATTEMPT = 3
MAX_ATTEMPTS_PER_DAY = 5
MAX_GUESSES = 5
OTP_DIGITS = 8


class ProofError(RuntimeError):
    def __init__(self, code: str, *, commit_counters: bool = False) -> None:
        super().__init__("address proof failed")
        self.code = code
        # True when the service intentionally flushed guess/expiry
        # counters before raising: the caller must COMMIT (not rollback)
        # so brute-force budgets persist. Counter rows only — committing
        # them can never create a claim, envelope, or binding.
        self.commit_counters = commit_counters


def otp_hmac(*, attempt_id: uuid.UUID, code: str, otp_key: bytes) -> bytes:
    if not otp_key or len(otp_key) < 32:
        raise ProofError("PROVIDER_UNAVAILABLE")
    return hmac.new(otp_key, f"{attempt_id}|{code}".encode("ascii"), hashlib.sha256).digest()


def attempt_mac(
    *,
    delivery_id: uuid.UUID,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    destination_tag: bytes,
    expires_at: datetime,
    channel: str,
    state: str,
    verified_at: datetime | None,
    mac_key: bytes,
) -> bytes:
    """Outside-DB evidence MAC (D14). Binds gift/account/session/address/
    expiry/channel/state so a DB-patched proof row cannot release escrow."""
    if not mac_key or len(mac_key) < 32:
        raise ProofError("PROVIDER_UNAVAILABLE")
    body = b"|".join(
        [
            b"v1|attempt|1",
            delivery_id.bytes,
            account_user_id.bytes,
            session_id.bytes,
            bytes(destination_tag),
            str(int(expires_at.timestamp())).encode("ascii"),
            channel.encode("ascii"),
            state.encode("ascii"),
            str(int(verified_at.timestamp()) if verified_at is not None else 0).encode("ascii"),
        ]
    )
    return hmac.new(mac_key, body, hashlib.sha256).digest()


def verify_attempt_mac(row: AddressProofAttempt, *, mac_key: bytes) -> bool:
    import hmac as _hmac

    try:
        expected = attempt_mac(
            delivery_id=row.delivery_id,
            account_user_id=row.account_user_id,
            session_id=row.session_id,
            destination_tag=bytes(row.destination_tag),
            expires_at=row.expires_at,
            channel="sms" if row.address_kind == "phone" else "email",
            state=row.state,
            verified_at=row.verified_at,
            mac_key=mac_key,
        )
    except ProofError:
        return False
    return _hmac.compare_digest(bytes(row.attempt_mac), expected)


def _live_delivery_or_raise(session: Session, delivery_id: uuid.UUID, now: datetime) -> PendingDelivery:
    delivery = session.get(PendingDelivery, delivery_id, with_for_update=True)
    if delivery is None or delivery.state != "PENDING":
        raise ProofError("GIFT_UNAVAILABLE")
    if delivery.claim_expires_at <= now:
        raise ProofError("PROOF_EXPIRED")
    return delivery


def start_verification(
    session: Session,
    *,
    delivery_id: uuid.UUID,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    confirm_destination: str,
    destination_key_32: bytes,
    destination_tag_key: bytes,
    registry_hmac_key: bytes,
    otp_key: bytes,
    proof_mac_key: bytes,
    email_sender: TransactionalEmailSender | None,
    sms_provider: SmsVerifyProvider | None,
    locale: str,
    now: datetime,
    attempt_id: uuid.UUID,
    code_supplier: Callable[[], str] | None = None,
) -> AddressProofAttempt:
    """Confirm the full destination against the stored sealed value, then
    dispatch to the STORED value (abuse friction, not address proof).

    EMAIL: server-generated OTP via the transactional sender; local HMAC
    verification later. SMS: provider-owned Verify lifecycle; the server
    never sees or stores the code (otp_hmac is zeros; check() consults
    the provider — never a local compare, never fake success).

    code_supplier is an injectable email-OTP source (tests pass a fixed
    code; production default is secrets-based). SMS ignores it: the
    provider owns code issuance.
    """
    from remanence.pending.routing import RoutingError

    delivery = _live_delivery_or_raise(session, delivery_id, now)
    # The committed destination travels sealed on the frozen route record;
    # compare the caller's confirmation against it, then dispatch to the
    # STORED value (abuse friction, not address proof).
    stored = _stored_destination(session, delivery, destination_key_32)
    if not hmac.compare_digest(confirm_destination, stored):
        raise ProofError("ADDRESS_UNAVAILABLE")
    tag = hmac.new(
        destination_tag_key, f"v1|proof|{delivery.id}|{stored}".encode("utf-8"), hashlib.sha256
    ).digest()
    address_kind = "phone" if delivery.channel == "sms" else "email"
    from remanence.pending.registry import lookup_tag as _registry_tag

    address_tag = _registry_tag(address_kind, stored, registry_hmac_key)
    recent = session.scalar(
        select(AddressProofAttempt).where(
            AddressProofAttempt.delivery_id == delivery.id,
            AddressProofAttempt.account_user_id == account_user_id,
            AddressProofAttempt.state == "CHALLENGE_PENDING",
            AddressProofAttempt.created_at > now - RESEND_COOLDOWN,
        )
    )
    if recent is not None:
        raise ProofError("RATE_LIMITED")
    day_count = session.scalar(
        select(func.count())
        .select_from(AddressProofAttempt)
        .where(
            AddressProofAttempt.delivery_id == delivery.id,
            AddressProofAttempt.account_user_id == account_user_id,
            AddressProofAttempt.created_at > now - timedelta(days=1),
        )
    )
    if (day_count or 0) >= MAX_ATTEMPTS_PER_DAY:
        raise ProofError("RATE_LIMITED")
    code = (
        code_supplier()
        if code_supplier is not None
        else "".join(secrets.choice("0123456789") for _ in range(OTP_DIGITS))
    )
    if delivery.channel == "email":
        if email_sender is None:
            raise ProofError("PROVIDER_UNAVAILABLE")
        otp_digest = otp_hmac(attempt_id=attempt_id, code=code, otp_key=otp_key)
    elif delivery.channel == "sms":
        if sms_provider is None:
            raise ProofError("PROVIDER_UNAVAILABLE")
        # Provider owns the code: nothing code-derived is stored here.
        otp_digest = bytes(32)
    else:
        raise ProofError("ADDRESS_UNAVAILABLE")
    attempt = AddressProofAttempt(
        id=attempt_id,
        delivery_id=delivery.id,
        account_user_id=account_user_id,
        session_id=session_id,
        state="CHALLENGE_PENDING",
        destination_tag=tag,
        address_kind=address_kind,
        address_lookup_tag=address_tag,
        provider_reference=None,
        otp_hmac=otp_digest,
        attempt_mac=attempt_mac(
            delivery_id=delivery.id,
            account_user_id=account_user_id,
            session_id=session_id,
            destination_tag=tag,
            expires_at=now + ATTEMPT_TTL,
            channel=delivery.channel,
            state="CHALLENGE_PENDING",
            verified_at=None,
            mac_key=proof_mac_key,
        ),
        guesses=0,
        dispatches=0,
        expires_at=now + ATTEMPT_TTL,
    )
    session.add(attempt)
    session.flush()
    try:
        if delivery.channel == "email":
            assert email_sender is not None
            reference = email_sender.send(
                attempt_id=str(attempt_id), stored_destination=stored, code=code, locale=locale
            )
        else:
            assert sms_provider is not None
            reference = sms_provider.start(
                attempt_id=str(attempt_id), stored_destination=stored, locale=locale
            )
    except Exception as exc:
        session.delete(attempt)
        session.flush()
        raise ProofError("PROVIDER_UNAVAILABLE") from exc
    attempt.provider_reference = reference
    attempt.dispatches = 1
    session.flush()
    return attempt


def _stored_destination(session: Session, delivery: PendingDelivery, destination_key_32: bytes) -> str:
    """Resolve the committed destination via the frozen sealed route."""
    from remanence.pending.models import PendingSendRoute

    if delivery.route_id is None:
        raise ProofError("ADDRESS_UNAVAILABLE")
    route = session.get(PendingSendRoute, delivery.route_id)
    if route is None:
        raise ProofError("ADDRESS_UNAVAILABLE")
    return open_destination(sealed=bytes(route.sealed_destination), key_32=destination_key_32)


def check_verification(
    session: Session,
    *,
    attempt_id: uuid.UUID,
    delivery_id: uuid.UUID,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    code: str,
    otp_key: bytes,
    sms_provider: SmsVerifyProvider | None,
    proof_mac_key: bytes,
    now: datetime,
) -> AddressProofAttempt:
    """EMAIL: local HMAC compare of the server-issued OTP. SMS: consult
    the provider (provider-owned code); a local compare here would be
    fake success. Either way, delivery/account/session/expiry/guesses
    are revalidated, with delivery locked BEFORE the attempt (unified
    order: delivery -> attempt; claim.py uses the same order)."""
    peek = session.get(AddressProofAttempt, attempt_id)
    if peek is None:
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    if peek.delivery_id != delivery_id:
        # R9: the attempt must belong to the path delivery — no
        # cross-delivery code replay. Generic denial, no oracle.
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    delivery = _live_delivery_or_raise(session, peek.delivery_id, now)
    attempt = session.get(AddressProofAttempt, attempt_id, with_for_update=True)
    assert attempt is not None
    if (
        attempt.account_user_id != account_user_id
        or attempt.session_id != session_id
        or attempt.state != "CHALLENGE_PENDING"
        or attempt.expires_at <= now
    ):
        raise ProofError("PROOF_EXPIRED")
    if attempt.guesses >= MAX_GUESSES:
        attempt.state = "EXPIRED"
        session.flush()
        raise ProofError("PROOF_EXPIRED", commit_counters=True)
    # Issuance MAC first: a DB-patched account/session/tag/expiry dies here.
    if not verify_attempt_mac(attempt, mac_key=proof_mac_key):
        attempt.state = "EXPIRED"
        session.flush()
        raise ProofError("PROOF_EXPIRED", commit_counters=True)
    if delivery.channel == "email":
        expected = otp_hmac(attempt_id=attempt.id, code=code, otp_key=otp_key)
        approved = hmac.compare_digest(bytes(attempt.otp_hmac), expected)
    elif delivery.channel == "sms":
        if sms_provider is None or attempt.provider_reference is None:
            raise ProofError("PROVIDER_UNAVAILABLE")
        try:
            outcome = sms_provider.check(
                attempt_id=str(attempt.id),
                provider_reference=attempt.provider_reference,
                code=code,
            )
        except Exception as exc:
            raise ProofError("PROVIDER_UNAVAILABLE") from exc
        if outcome == "expired":
            attempt.state = "EXPIRED"
            session.flush()
            raise ProofError("PROOF_EXPIRED", commit_counters=True)
        approved = outcome == "approved"
    else:
        raise ProofError("ADDRESS_UNAVAILABLE")
    if not approved:
        attempt.guesses += 1
        if attempt.guesses >= MAX_GUESSES:
            attempt.state = "EXPIRED"
        session.flush()
        raise ProofError("ADDRESS_PROOF_REQUIRED", commit_counters=True)
    attempt.state = "VERIFIED"
    attempt.verified_at = now
    attempt.attempt_mac = attempt_mac(
        delivery_id=attempt.delivery_id,
        account_user_id=attempt.account_user_id,
        session_id=attempt.session_id,
        destination_tag=bytes(attempt.destination_tag),
        expires_at=attempt.expires_at,
        channel=delivery.channel,
        state="VERIFIED",
        verified_at=now,
        mac_key=proof_mac_key,
    )
    session.flush()
    return attempt


def verify_google_shortcut(
    session: Session,
    *,
    delivery_id: uuid.UUID,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    google_subject: str,
    google_email_normalized: str,
    google_issued_at: datetime,
    account_google_subject: str,
    google_authority: str,
    destination_key_32: bytes,
    destination_tag_key: bytes,
    registry_hmac_key: bytes,
    proof_mac_key: bytes,
    now: datetime,
) -> AddressProofAttempt:
    """Same-email shortcut: fresh (<=10min) nonce-verified Google claims
    for the signed-in account's exact subject, destination equals the
    claim email, AND Google is authoritative for the address: Gmail, or a
    verified Workspace address carrying hd. Arbitrary third-party
    email_verified (fresh subject alone) is NOT authority and falls
    through to OTP — never approval here. The attempt carries the same
    destination-tag scheme as the OTP path."""
    delivery = _live_delivery_or_raise(session, delivery_id, now)
    if google_authority not in ("gmail", "workspace_hd"):
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    if (
        google_subject != account_google_subject
        or now - google_issued_at > timedelta(minutes=10)
    ):
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    stored = _stored_destination(session, delivery, destination_key_32)
    if not hmac.compare_digest(google_email_normalized, stored):
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    from remanence.pending.registry import lookup_tag as _registry_tag2

    attempt = AddressProofAttempt(
        id=uuid.uuid4(),
        delivery_id=delivery_id,
        account_user_id=account_user_id,
        session_id=session_id,
        state="VERIFIED",
        destination_tag=hmac.new(
            destination_tag_key, f"v1|proof|{delivery.id}|{stored}".encode("utf-8"), hashlib.sha256
        ).digest(),
        address_kind="email",
        address_lookup_tag=_registry_tag2("email", stored, registry_hmac_key),
        provider_reference=None,
        otp_hmac=hashlib.sha256(f"google|{delivery_id}|{account_user_id}".encode()).digest(),
        attempt_mac=b"",
        guesses=0,
        dispatches=0,
        expires_at=now + ATTEMPT_TTL,
        verified_at=now,
    )
    attempt.attempt_mac = attempt_mac(
        delivery_id=delivery_id,
        account_user_id=account_user_id,
        session_id=session_id,
        destination_tag=bytes(attempt.destination_tag),
        expires_at=attempt.expires_at,
        channel="email",
        state="VERIFIED",
        verified_at=now,
        mac_key=proof_mac_key,
    )
    session.add(attempt)
    session.flush()
    return attempt
