"""Frozen send routing (T185 M2). No silent v1<->pending fallback.

Decision (pure, unit-tested):
- handle, or active VERIFIED email/phone with exactly one owner AND a
  usable active bundle -> EXISTING_USER_V1. No escrow, no claim.
- absent/RESERVED binding -> PENDING_V2 (fixed target, original contract).
- RETIRED address, conflicting/malformed evidence, disabled owner,
  blocked sender, missing or incompatible bundle -> explicit error.
  NEVER silent escrow, NEVER absent->pending fallback for retired rows.

Destinations at rest are AEAD-sealed (dev adapter with an explicit
synthetic key in dev/test; KMS envelope in production). Raw recipient
destinations never reach SQL or idempotency metadata.

Race policy: route freeze is the linearization point, taken under the
same address lock as the registry reads. Binding before freeze -> v1;
freeze first -> frozen v2 even if binding lands later.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.pending.models import PendingSendRoute
from remanence.pending.registry import LiveBinding, address_fate, live_verified, lock_tag, lookup_tag
from remanence.pending.vault import VaultError, aead_from_raw_key
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

ROUTE_TTL = timedelta(hours=24)
_DESTINATION_AAD = b"pending-route-destination-v1"


class RoutingError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("send route failed")
        self.code = code


def seal_destination(*, normalized: str, key_32: bytes, mode: str) -> bytes:
    """Encrypt-at-rest dev adapter for route destinations. Dev/test only
    with an explicit synthetic key; prod/beta refused (KMS envelope is
    the production path, wired at deployment)."""
    if mode in ("prod", "beta"):
        raise RoutingError("PROTOCOL_UNSUPPORTED")
    try:
        primitive = aead_from_raw_key(key_32)
        return primitive.encrypt(normalized.encode("utf-8"), _DESTINATION_AAD)
    except VaultError as exc:
        raise RoutingError("ADDRESS_UNAVAILABLE") from exc


def open_destination(*, sealed: bytes, key_32: bytes) -> str:
    import tink

    try:
        primitive = aead_from_raw_key(key_32)
        return primitive.decrypt(sealed, _DESTINATION_AAD).decode("utf-8")
    except (VaultError, tink.TinkError, UnicodeDecodeError) as exc:
        raise RoutingError("ADDRESS_UNAVAILABLE") from exc


@dataclass(frozen=True)
class RouteDecision:
    mode: str  # EXISTING_USER_V1 | PENDING_V2
    user_id: uuid.UUID | None = None
    bundle_id: uuid.UUID | None = None
    delivery_target_id: uuid.UUID | None = None
    binding_revision: int | None = None


def decide_route(
    *,
    destination_kind: str,
    live: LiveBinding | None,
    active_bundle_id: uuid.UUID | None,
    recipient_disabled: bool = False,
    sender_blocked: bool = False,
    address_retired: bool = False,
) -> RouteDecision:
    """Pure decision. Handle destinations pass live=None + bundle directly
    (caller resolves the handle to its fixed user/bundle first). A retired
    address errors explicitly — it must never fall back to pending."""
    if sender_blocked or recipient_disabled or address_retired:
        raise RoutingError("ADDRESS_UNAVAILABLE")
    if live is not None:
        if active_bundle_id is None:
            # Verified binding without a usable bundle is an error, never
            # permission to silently use escrow.
            raise RoutingError("RECIPIENT_KEY_STALE")
        return RouteDecision(
            mode="EXISTING_USER_V1",
            user_id=live.owner_user_id,
            bundle_id=active_bundle_id,
            binding_revision=live.revision,
        )
    return RouteDecision(mode="PENDING_V2", delivery_target_id=uuid.uuid4())


def active_bundle_id_for(session: Session, user_id: uuid.UUID) -> uuid.UUID | None:
    """Authoritative usable bundle for v1 routing. None means error, not escrow."""
    bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == user_id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    return bundle.id if bundle is not None else None


def decide_for_send(
    session: Session,
    *,
    destination_kind: str,
    normalized_destination: str,
    active_bundle_id: uuid.UUID | None,
    hmac_key: bytes,
    evidence_key: bytes,
    sender_user_id: uuid.UUID,
    recipient_disabled: bool = False,
    sender_blocked: bool = False,
) -> RouteDecision:
    """Registry-backed decision under the address lock (same lock the
    freeze takes). Handles resolve through the directory to their fixed
    user + authoritative active bundle (existing v1 policy); email/phone
    consult the verified registry with the evidence MAC enforced — a
    forged VERIFIED row errors instead of routing to an attacker-known
    v1 bundle. The owner row is loaded (disabled owners error) and the
    existing UserBlock policy applies (recipient-blocked sender errors),
    mirroring v1 draft/incoming. A verified address without a usable
    bundle errors, never silent escrow."""
    from remanence.users.safety_models import UserBlock

    if destination_kind == "handle":
        user = session.scalar(
            select(User).where(User.handle_normalized == normalized_destination)
        )
        if user is None or user.disabled_at is not None:
            raise RoutingError("ADDRESS_UNAVAILABLE")
        if session.get(UserBlock, (user.id, sender_user_id)) is not None:
            raise RoutingError("ADDRESS_UNAVAILABLE")
        bundle_id = active_bundle_id_for(session, user.id)
        if bundle_id is None:
            raise RoutingError("RECIPIENT_KEY_STALE")
        return RouteDecision(mode="EXISTING_USER_V1", user_id=user.id, bundle_id=bundle_id)
    if destination_kind not in ("email", "phone"):
        raise RoutingError("ADDRESS_UNAVAILABLE")
    fate = address_fate(
        session, kind=destination_kind, normalized=normalized_destination, hmac_key=hmac_key
    )
    if fate == "RETIRED":
        raise RoutingError("ADDRESS_UNAVAILABLE")
    live = None
    authoritative_bundle: uuid.UUID | None = active_bundle_id
    if fate == "VERIFIED":
        from remanence.pending.registry import RegistryError as _RegistryError

        try:
            live = live_verified(
                session,
                kind=destination_kind,
                normalized=normalized_destination,
                hmac_key=hmac_key,
                evidence_key=evidence_key,
            )
        except _RegistryError as exc:
            raise RoutingError("ADDRESS_UNAVAILABLE") from exc
        assert live is not None
        owner = session.get(User, live.owner_user_id)
        if owner is None or owner.disabled_at is not None:
            raise RoutingError("ADDRESS_UNAVAILABLE")
        if (
            session.get(UserBlock, (live.owner_user_id, sender_user_id)) is not None
            or sender_blocked
        ):
            raise RoutingError("ADDRESS_UNAVAILABLE")
        # Authoritative usable bundle wins: a verified address with no
        # usable bundle errors (never silent escrow), and a stale
        # caller-supplied bundle is rejected rather than trusted.
        authoritative_bundle = active_bundle_id_for(session, live.owner_user_id)
        if authoritative_bundle is None:
            raise RoutingError("RECIPIENT_KEY_STALE")
        if active_bundle_id is not None and active_bundle_id != authoritative_bundle:
            raise RoutingError("ADDRESS_UNAVAILABLE")
    return decide_route(
        destination_kind=destination_kind,
        live=live,
        active_bundle_id=authoritative_bundle,
        recipient_disabled=recipient_disabled,
        sender_blocked=sender_blocked,
    )


def freeze_route(
    session: Session,
    *,
    sender_user_id: uuid.UUID,
    capsule_id: uuid.UUID,
    destination_kind: str,
    normalized_destination: str,
    destination_key_32: bytes,
    destination_key_mode: str,
    hmac_key: bytes,
    decision: RouteDecision,
    now: datetime,
    pending_sends_enabled: bool,
) -> PendingSendRoute:
    """Freeze under the same address lock, persisting only the SEALED
    destination. Raw recipient destinations never reach SQL or
    idempotency metadata."""
    if destination_kind not in ("email", "phone", "handle"):
        raise RoutingError("ADDRESS_UNAVAILABLE")
    if decision.mode == "PENDING_V2" and not pending_sends_enabled:
        # Unresolved route with sends disabled: explicit unavailable, never
        # a fabricated existing user.
        raise RoutingError("PROTOCOL_UNSUPPORTED")
    if destination_kind in ("email", "phone"):
        lock_tag(
            session,
            destination_kind,
            lookup_tag(destination_kind, normalized_destination, hmac_key),
        )
    sealed = seal_destination(
        normalized=normalized_destination, key_32=destination_key_32, mode=destination_key_mode
    )
    row = PendingSendRoute(
        id=uuid.uuid4(),
        sender_user_id=sender_user_id,
        capsule_id=capsule_id,
        mode=decision.mode,
        destination_kind=destination_kind,
        sealed_destination=sealed,
        target_user_id=decision.user_id,
        target_bundle_id=decision.bundle_id,
        delivery_target_id=decision.delivery_target_id,
        binding_revision=decision.binding_revision,
        expires_at=now + ROUTE_TTL,
    )
    session.add(row)
    session.flush()
    return row


def check_finalize_route(
    session: Session,
    *,
    route_id: uuid.UUID,
    sender_user_id: uuid.UUID,
    capsule_id: uuid.UUID,
    mode: str,
    now: datetime,
) -> PendingSendRoute:
    """Draft/finalize must present the matching frozen route. Expired or
    mismatched routes reject before any key intake or publication."""
    route = session.get(PendingSendRoute, route_id)
    if (
        route is None
        or route.sender_user_id != sender_user_id
        or route.capsule_id != capsule_id
        or route.mode != mode
        or route.expires_at <= now
    ):
        raise RoutingError("ROUTE_STALE")
    return route
