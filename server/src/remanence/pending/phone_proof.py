"""Find first gifts by a proven phone number (ADR-020).

An SMS goes out only when a live gift exists for the number, and the caller
gets the same answer either way, so this cannot be used to probe numbers or to
bomb a stranger. Budgets count the NUMBER, not the account, because a new
account is free. The server generates the code and the provider only carries it.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.pending.claim_models import AddressProofAttempt, PendingDelivery
from remanence.pending.proof import ATTEMPT_TTL, MAX_GUESSES, ProofError, attempt_mac, otp_hmac, verify_attempt_mac
from remanence.pending.publication import expire_card_gifts
from remanence.pending.registry import lock_tag, lookup_tag

logger = logging.getLogger(__name__)

OTP_DIGITS = 6
RESEND_COOLDOWN = timedelta(seconds=60)
MAX_ATTEMPTS_PER_NUMBER_PER_DAY = 5
MAX_GIFTS_PER_PROOF = 20
SMS_TEXT = "{code} is your Remanence code. Do not share it.\n\n@{host} #{code}"


def country_allowed(normalized_e164: str, prefixes: list[str]) -> bool:
    return any(normalized_e164.startswith("+" + prefix) for prefix in prefixes)


def card_hint(normalized_e164: str) -> str:
    return "••••" + normalized_e164[-2:]


@dataclass(frozen=True)
class ProofStart:
    proof_id: uuid.UUID
    expires_at: datetime
    # Present only when a message must go out. Sent after commit and after the
    # response, so response timing cannot reveal whether a gift exists.
    dispatch_to: str | None = None
    dispatch_text: str | None = None


def _live_gifts(session: Session, *, tag: bytes, now: datetime) -> list[PendingDelivery]:
    return list(
        session.scalars(
            select(PendingDelivery)
            .where(
                PendingDelivery.destination_lookup_tag == tag,
                PendingDelivery.state == "PENDING",
                PendingDelivery.claim_expires_at > now,
            )
            .order_by(PendingDelivery.created_at.desc(), PendingDelivery.id)
            .limit(MAX_GIFTS_PER_PROOF)
        ).all()
    )


def start_phone_proof(
    session: Session,
    *,
    normalized_phone: str,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    registry_hmac_key: bytes,
    destination_tag_key: bytes,
    otp_key: bytes,
    proof_mac_key: bytes,
    now: datetime,
    allowed_prefixes: list[str],
    daily_sms_cap: int,
    sms_host: str,
) -> ProofStart:
    proof_id = uuid.uuid4()
    silent = ProofStart(proof_id=proof_id, expires_at=now + ATTEMPT_TTL)
    expire_card_gifts(session, now=now)
    if not country_allowed(normalized_phone, allowed_prefixes):
        return silent
    tag = lookup_tag("phone", normalized_phone, registry_hmac_key)
    lock_tag(session, "phone", tag)
    gifts = _live_gifts(session, tag=tag, now=now)
    if not gifts:
        return silent
    recent = session.scalar(
        select(func.count())
        .select_from(AddressProofAttempt)
        .where(
            AddressProofAttempt.address_lookup_tag == tag,
            AddressProofAttempt.created_at > now - RESEND_COOLDOWN,
        )
    )
    today = session.scalar(
        select(func.count())
        .select_from(AddressProofAttempt)
        .where(
            AddressProofAttempt.address_lookup_tag == tag,
            AddressProofAttempt.dispatches > 0,
            AddressProofAttempt.created_at > now - timedelta(days=1),
        )
    )
    if recent or (today or 0) >= MAX_ATTEMPTS_PER_NUMBER_PER_DAY:
        return silent
    spent = session.scalar(
        select(func.count())
        .select_from(AddressProofAttempt)
        .where(
            AddressProofAttempt.dispatches > 0,
            AddressProofAttempt.created_at > now - timedelta(days=1),
        )
    )
    if (spent or 0) >= daily_sms_cap:
        logger.warning("pending sms daily ceiling reached")
        return silent
    code = "".join(secrets.choice("0123456789") for _ in range(OTP_DIGITS))
    primary = gifts[0]
    destination_tag = hmac.new(
        destination_tag_key, f"v1|proof|{primary.id}|{normalized_phone}".encode("utf-8"), hashlib.sha256
    ).digest()
    expires_at = now + ATTEMPT_TTL
    session.add(
        AddressProofAttempt(
            id=proof_id,
            delivery_id=primary.id,
            account_user_id=account_user_id,
            session_id=session_id,
            state="CHALLENGE_PENDING",
            destination_tag=destination_tag,
            address_kind="phone",
            address_lookup_tag=tag,
            provider_reference=None,
            otp_hmac=otp_hmac(attempt_id=proof_id, code=code, otp_key=otp_key),
            attempt_mac=attempt_mac(
                delivery_id=primary.id,
                account_user_id=account_user_id,
                session_id=session_id,
                destination_tag=destination_tag,
                expires_at=expires_at,
                channel="sms",
                state="CHALLENGE_PENDING",
                verified_at=None,
                mac_key=proof_mac_key,
            ),
            guesses=0,
            dispatches=1,
            expires_at=expires_at,
        )
    )
    session.flush()
    return ProofStart(
        proof_id=proof_id,
        expires_at=expires_at,
        dispatch_to=normalized_phone,
        dispatch_text=SMS_TEXT.format(code=code, host=sms_host),
    )


def _verified_attempt(
    *,
    gift: PendingDelivery,
    template: AddressProofAttempt,
    normalized_tag_key: bytes,
    proof_mac_key: bytes,
    now: datetime,
) -> AddressProofAttempt:
    """Every live gift on the proven number gets its own VERIFIED attempt, so
    the claim path stays exactly per-delivery."""
    destination_tag = hmac.new(
        normalized_tag_key, b"v1|proof-sibling|" + gift.id.bytes + bytes(template.address_lookup_tag), hashlib.sha256
    ).digest()
    attempt = AddressProofAttempt(
        id=uuid.uuid4(),
        delivery_id=gift.id,
        account_user_id=template.account_user_id,
        session_id=template.session_id,
        state="VERIFIED",
        destination_tag=destination_tag,
        address_kind="phone",
        address_lookup_tag=bytes(template.address_lookup_tag),
        provider_reference=None,
        otp_hmac=bytes(template.otp_hmac),
        attempt_mac=b"",
        guesses=0,
        dispatches=0,
        expires_at=template.expires_at,
        verified_at=now,
    )
    attempt.attempt_mac = attempt_mac(
        delivery_id=gift.id,
        account_user_id=attempt.account_user_id,
        session_id=attempt.session_id,
        destination_tag=destination_tag,
        expires_at=attempt.expires_at,
        channel="sms",
        state="VERIFIED",
        verified_at=now,
        mac_key=proof_mac_key,
    )
    return attempt


def check_phone_proof(
    session: Session,
    *,
    proof_id: uuid.UUID,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    code: str,
    otp_key: bytes,
    proof_mac_key: bytes,
    destination_tag_key: bytes,
    now: datetime,
) -> list[tuple[PendingDelivery, AddressProofAttempt]]:
    """A proof that never existed fails exactly like a wrong code."""
    attempt = session.get(AddressProofAttempt, proof_id, with_for_update=True)
    if (
        attempt is None
        or attempt.address_kind != "phone"
        or attempt.account_user_id != account_user_id
        or attempt.session_id != session_id
    ):
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    if attempt.expires_at <= now or attempt.state not in ("CHALLENGE_PENDING", "VERIFIED"):
        raise ProofError("ADDRESS_PROOF_REQUIRED")
    if not verify_attempt_mac(attempt, mac_key=proof_mac_key):
        attempt.state = "EXPIRED"
        session.flush()
        raise ProofError("ADDRESS_PROOF_REQUIRED", commit_counters=True)
    if attempt.guesses >= MAX_GUESSES:
        attempt.state = "EXPIRED"
        session.flush()
        raise ProofError("ADDRESS_PROOF_REQUIRED", commit_counters=True)
    expected = otp_hmac(attempt_id=attempt.id, code=code, otp_key=otp_key)
    if not hmac.compare_digest(bytes(attempt.otp_hmac), expected):
        attempt.guesses += 1
        if attempt.guesses >= MAX_GUESSES:
            attempt.state = "EXPIRED"
        session.flush()
        raise ProofError("ADDRESS_PROOF_REQUIRED", commit_counters=True)
    if attempt.state == "CHALLENGE_PENDING":
        attempt.state = "VERIFIED"
        attempt.verified_at = now
        attempt.attempt_mac = attempt_mac(
            delivery_id=attempt.delivery_id,
            account_user_id=attempt.account_user_id,
            session_id=attempt.session_id,
            destination_tag=bytes(attempt.destination_tag),
            expires_at=attempt.expires_at,
            channel="sms",
            state="VERIFIED",
            verified_at=now,
            mac_key=proof_mac_key,
        )
    gifts = _live_gifts(session, tag=bytes(attempt.address_lookup_tag), now=now)
    result: list[tuple[PendingDelivery, AddressProofAttempt]] = []
    for gift in gifts:
        if gift.id == attempt.delivery_id:
            result.append((gift, attempt))
            continue
        sibling = session.scalar(
            select(AddressProofAttempt).where(
                AddressProofAttempt.delivery_id == gift.id,
                AddressProofAttempt.account_user_id == account_user_id,
                AddressProofAttempt.session_id == session_id,
                AddressProofAttempt.state == "VERIFIED",
                AddressProofAttempt.expires_at > now,
            )
        )
        if sibling is None:
            sibling = _verified_attempt(
                gift=gift,
                template=attempt,
                normalized_tag_key=destination_tag_key,
                proof_mac_key=proof_mac_key,
                now=now,
            )
            session.add(sibling)
        result.append((gift, sibling))
    session.flush()
    return result
