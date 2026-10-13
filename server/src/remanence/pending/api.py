"""Experimental pending-gift HTTP router (T185 M3). NOT mounted in main.py.

Stage: scaffold with service-backed handlers. Every route requires the
master pending flag and its sub-flag; otherwise 404. Production without
configured custody/vault/signers fails closed (503/404, never fake
success). DTO shapes are the frozen §8 contract: strict schemas
(extra=forbid), unknown fields rejected, exact field names.

Isolated tests mount ONLY this router on a synthetic app; the live beta
prefix NEVER receives pending traffic.
"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.pending import claim as claim_service
from remanence.pending import locator_store, proof as proof_service
from remanence.pending import profiles as profile_service
from remanence.pending import publication as publication_service
from remanence.pending import retire as retire_service
from remanence.pending import routing as routing_service
from remanence.pending.claim import ClaimError
from remanence.pending.locator import normalize_code
from remanence.pending.locator_store import LocatorStoreError
from remanence.pending.proof import ProofError
from remanence.pending.profiles import ProfileError
from remanence.pending.publication import PublicationError
from remanence.pending.retire import RetireError
from remanence.pending.routing import RoutingError

router = APIRouter()


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# --- frozen DTOs (§8) -------------------------------------------------------------


class Destination(Strict):
    kind: str = Field(pattern="^(email|phone|handle)$")
    value: str = Field(min_length=1, max_length=320)


class SendRouteRequest(Strict):
    capsule_id: uuid.UUID
    destination: Destination
    idempotency_key: uuid.UUID


class PendingDeliveryRequest(Strict):
    route_id: uuid.UUID
    capsule_id: uuid.UUID
    delivery_target_id: uuid.UUID
    channel: str = Field(pattern="^(email|sms)$")
    destination: str = Field(min_length=1, max_length=320)
    salt_b64: str
    claim_expires_at: int = Field(ge=0)
    claim_policy_version: int
    artifacts: list[dict] = Field(default_factory=list)
    idempotency_key: uuid.UUID


class FinalizeRequest(Strict):
    publish_statement_b64: str
    publish_signature_69_b64: str
    escrow_payload_b64: str
    idempotency_key: uuid.UUID


class GiftLookupRequest(Strict):
    code: str = Field(min_length=1, max_length=32)


class VerificationRequest(Strict):
    confirm_destination: str = Field(min_length=1, max_length=320)
    consent: dict | None = None
    idempotency_key: uuid.UUID


class VerificationCheckRequest(Strict):
    code: str = Field(min_length=1, max_length=32)


class ClaimRequest(Strict):
    claim_intent_b64: str
    claim_intent_signature_69_b64: str
    idempotency_key: uuid.UUID


class AckRequest(Strict):
    claim_sha256_hex: str = Field(min_length=64, max_length=64)
    envelope_sha256_hex: str = Field(min_length=64, max_length=64)


class HandleRequest(Strict):
    handle: str = Field(min_length=1, max_length=32)


class LocatorCompletionRequest(Strict):
    publication_sha256_hex: str = Field(min_length=64, max_length=64)
    operation_id: uuid.UUID


# --- gating ------------------------------------------------------------------------


def _problem(code: str, status: int) -> JSONResponse:
    return JSONResponse(
        {"type": "about:blank", "title": code, "status": status, "code": code},
        status_code=status,
        headers={"Cache-Control": "no-store"},
    )


def _gate(request: Request, subflag: str) -> Any:
    settings = request.app.state.settings
    if not settings.pending_protocol_enabled or not getattr(settings, subflag, False):
        return _problem("PROTOCOL_UNSUPPORTED", 404)
    ctx = getattr(request.app.state, "pending_ctx", None)
    if ctx is None:
        return _problem("PROVIDER_UNAVAILABLE", 503)
    return ctx


class LookupRateLimiter:
    """Anonymous lookup budgets: 10/minute + 100/day per client bucket +
    a global per-minute ceiling. Bucket keys are SHA-256 digests of the
    client address — raw IPs (or any PII) are never stored or logged.
    In-process and per-instance: sufficient for the isolated experimental
    surface; production needs shared state (documented, not claimed)."""

    MINUTE_CAP = 10
    DAY_CAP = 100
    GLOBAL_PER_MINUTE = 1000

    def __init__(self) -> None:
        self._minute: dict[str, list[float]] = {}
        self._day: dict[str, list[float]] = {}
        self._global_minute: list[float] = []

    @staticmethod
    def bucket_key(client_host: str) -> str:
        import hashlib as _hashlib

        return _hashlib.sha256(f"v1|gift-lookup|{client_host}".encode()).hexdigest()

    def check(self, client_host: str, *, now_ts: float) -> bool:
        import time as _time

        key = self.bucket_key(client_host)
        minute_ago = now_ts - 60.0
        day_ago = now_ts - 86400.0
        self._global_minute = [t for t in self._global_minute if t > minute_ago]
        if len(self._global_minute) >= self.GLOBAL_PER_MINUTE:
            return False
        minutes = [t for t in self._minute.get(key, []) if t > minute_ago]
        days = [t for t in self._day.get(key, []) if t > day_ago]
        if len(minutes) >= self.MINUTE_CAP or len(days) >= self.DAY_CAP:
            return False
        minutes.append(now_ts)
        days.append(now_ts)
        self._minute[key] = minutes
        self._day[key] = days
        self._global_minute.append(now_ts)
        return True


def _lookup_limiter(request: Request) -> LookupRateLimiter:
    limiter = getattr(request.app.state, "pending_lookup_limiter", None)
    if limiter is None:
        limiter = LookupRateLimiter()
        request.app.state.pending_lookup_limiter = limiter
    return limiter


def _map_error(exc: Exception) -> JSONResponse:
    code = getattr(exc, "code", "INTERNAL_ERROR")
    status = {
        "GIFT_UNAVAILABLE": 404,
        "ROUTE_STALE": 409,
        "IDEMPOTENCY_CONFLICT": 409,
        "CLAIM_CONFLICT": 409,
        "HANDLE_IMMUTABLE": 409,
        "RATE_LIMITED": 429,
        "CLIENT_UPGRADE_REQUIRED": 426,
        "PROTOCOL_UNSUPPORTED": 404,
        "RECIPIENT_KEY_STALE": 409,
    }.get(code, 400)
    return _problem(code, status)


def _b64decode(value: str, *, max_bytes: int = 8192) -> bytes:
    try:
        raw = base64.b64decode(value, validate=True)
    except Exception as exc:
        raise PublicationError("PROTOCOL_UNSUPPORTED") from exc
    if not raw or len(raw) > max_bytes:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    return raw


# --- routes --------------------------------------------------------------------------


class GoogleReceiveRegistration(Strict):
    flow: uuid.UUID
    registration_token: str = Field(min_length=1, max_length=256)
    key_bundle_id: uuid.UUID
    suite: str = Field(min_length=1, max_length=80)
    protocol_version: int
    encryption_public_keyset_b64: str
    signing_public_keyset_b64: str


@router.post("/v2/auth/google/receive-register")
def google_receive_register(
    payload: GoogleReceiveRegistration, request: Request,
    db: Session = Depends(get_db_session),
):
    """Capability-negotiated receive registration: consumes the SAME
    verified REGISTER ticket / GoogleIdentity flow as v1, but creates a
    handleless account (profile version 2, handle null). No bare-email
    path, no auto-link, no second session minted. Gated on the master
    pending flag (receive profile), not on sends."""
    from datetime import datetime as _datetime

    from sqlalchemy.exc import IntegrityError as _IntegrityError

    from remanence.api.google_auth import fail as _google_fail
    from remanence.api.google_auth import pending as _ticket

    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        row = _ticket(db, payload.flow, "REGISTER", payload.registration_token)
        result = profile_service.register_receive(
            db,
            ticket=row,
            key_bundle_id=payload.key_bundle_id,
            suite=payload.suite,
            protocol_version=payload.protocol_version,
            encryption_public_keyset=_b64decode(
                payload.encryption_public_keyset_b64, max_bytes=4096
            ),
            signing_public_keyset=_b64decode(
                payload.signing_public_keyset_b64, max_bytes=4096
            ),
            now=_datetime.now(timezone.utc),
            pending_protocol_enabled=True,
        )
        db.commit()
    except ProfileError as exc:
        db.rollback()
        return _map_error(exc)
    except _IntegrityError:
        db.rollback()
        return _problem("REGISTRATION_CONFLICT", 409)
    except Exception:
        db.rollback()
        return _google_fail()
    return {
        "status": "SIGNED_IN",
        "user": {
            "user_id": str(result.user_id),
            "email": result.email,
            "handle": None,
            "profile_version": 2,
        },
        "active_key_bundle_id": str(result.active_key_bundle_id),
        "session_id": str(result.session_id),
    }


@router.post("/v2/send-routes")
def send_routes(
    payload: SendRouteRequest, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_sends_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    from remanence.pending.normalize import (
        normalize_email_destination,
        normalize_phone_destination,
    )

    try:
        kind = payload.destination.kind
        if kind == "email":
            normalized = normalize_email_destination(payload.destination.value)
        elif kind == "phone":
            normalized = normalize_phone_destination(payload.destination.value)
        else:
            normalized = payload.destination.value
        decision = routing_service.decide_for_send(
            db,
            destination_kind=kind,
            normalized_destination=normalized,
            active_bundle_id=None,
            hmac_key=ctx["hmac_key"],
        )
        route = routing_service.freeze_route(
            db,
            sender_user_id=principal.user_id,
            capsule_id=payload.capsule_id,
            destination_kind=kind,
            normalized_destination=normalized,
            destination_key_32=ctx["destination_key"],
            destination_key_mode=request.app.state.settings.mode.value,
            hmac_key=ctx["hmac_key"],
            decision=decision,
            now=datetime.now(timezone.utc),
            pending_sends_enabled=True,
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    body: dict[str, Any] = {"route_id": str(route.id), "mode": route.mode}
    if route.mode == "EXISTING_USER_V1":
        body.update(
            user_id=str(route.target_user_id),
            key_bundle_id=str(route.target_bundle_id),
            binding_revision=route.binding_revision,
        )
    else:
        body.update(delivery_target_id=str(route.delivery_target_id))
    return body


@router.post("/v2/pending-deliveries")
def create_pending_delivery(
    payload: PendingDeliveryRequest, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_sends_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        route = routing_service.check_finalize_route(
            db,
            route_id=payload.route_id,
            sender_user_id=principal.user_id,
            capsule_id=payload.capsule_id,
            mode="PENDING_V2",
            now=datetime.now(timezone.utc),
        )
        _ = route
        delivery = publication_service.create_draft(
            db,
            sender_user_id=principal.user_id,
            capsule_id=payload.capsule_id,
            delivery_target_id=payload.delivery_target_id,
            route_id=payload.route_id,
            channel=payload.channel,
            claim_expires_at=datetime.fromtimestamp(payload.claim_expires_at, timezone.utc),
            now=datetime.now(timezone.utc),
            pending_sends_enabled=True,
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {"delivery_id": str(delivery.id), "state": delivery.state}


@router.post("/v2/pending-deliveries/{delivery_id}/finalize")
def finalize_pending_delivery(
    delivery_id: uuid.UUID, payload: FinalizeRequest, request: Request,
    db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_sends_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        delivery, code = publication_service.finalize_delivery(
            db,
            delivery_id=delivery_id,
            principal_user_id=principal.user_id,
            statement_raw=_b64decode(payload.publish_statement_b64, max_bytes=8192),
            signature_69=_b64decode(payload.publish_signature_69_b64, max_bytes=128),
            escrow_payload_raw=_b64decode(payload.escrow_payload_b64, max_bytes=2048),
            vault=ctx["vault"],
            expected_service_key_id=ctx["service_key_id"],
            locator_hmac_key=ctx["hmac_key"],
            locator_code_key_32=ctx["locator_code_key"],
            locator_code_key_mode=request.app.state.settings.mode.value,
            hint_mask="pending",
            now=datetime.now(timezone.utc),
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {"delivery_id": str(delivery.id), "state": delivery.state, "locator_code": code}


@router.post("/v2/pending-deliveries/{delivery_id}/cancel")
def cancel_pending_delivery(
    delivery_id: uuid.UUID, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_sends_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        publication_service.cancel_delivery(
            db, delivery_id=delivery_id, sender_user_id=principal.user_id
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {"delivery_id": str(delivery_id), "state": "CANCELLED"}


@router.post("/v2/pending-deliveries/{delivery_id}/verification")
def start_proof(
    delivery_id: uuid.UUID, payload: VerificationRequest, request: Request,
    db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_claims_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        attempt = proof_service.start_verification(
            db,
            delivery_id=delivery_id,
            account_user_id=principal.user_id,
            session_id=principal.session_id,
            confirm_destination=payload.confirm_destination,
            destination_key_32=ctx["destination_key"],
            destination_tag_key=ctx["hmac_key"],
            registry_hmac_key=ctx["hmac_key"],
            otp_key=ctx["otp_key"],
            proof_mac_key=ctx["proof_mac_key"],
            email_sender=ctx.get("email_sender"),
            sms_provider=ctx.get("sms_provider"),
            locale="en",
            now=datetime.now(timezone.utc),
            attempt_id=payload.idempotency_key,
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {"attempt_id": str(attempt.id), "expires_at": int(attempt.expires_at.timestamp())}


@router.post("/v2/pending-deliveries/{delivery_id}/verification/{attempt_id}/check")
def check_proof(
    delivery_id: uuid.UUID, attempt_id: uuid.UUID, payload: VerificationCheckRequest,
    request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_claims_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    _ = delivery_id
    try:
        attempt = proof_service.check_verification(
            db,
            attempt_id=attempt_id,
            delivery_id=delivery_id,
            account_user_id=principal.user_id,
            session_id=principal.session_id,
            code=payload.code,
            otp_key=ctx["otp_key"],
            sms_provider=ctx.get("sms_provider"),
            proof_mac_key=ctx["proof_mac_key"],
            now=datetime.now(timezone.utc),
        )
        db.commit()
    except ProofError as exc:
        # Intentional counter updates (wrong guesses, expiries) must
        # COMMIT — a rollback would grant unlimited guesses. Counter
        # rows only; committing them can never create a claim.
        if exc.commit_counters:
            try:
                db.commit()
            except Exception:
                db.rollback()
        else:
            db.rollback()
        return _map_error(exc)
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {"attempt_id": str(attempt.id), "state": attempt.state}


@router.post("/v2/pending-deliveries/{delivery_id}/claim/options")
def claim_challenge(
    delivery_id: uuid.UUID, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    """Frozen R10: empty request body; the server resolves the principal's
    usable VERIFIED attempt (same account/session, unexpired)."""
    from sqlalchemy import select as _select

    from remanence.pending.claim_models import AddressProofAttempt

    ctx = _gate(request, "pending_claims_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        attempt = db.scalar(
            _select(AddressProofAttempt).where(
                AddressProofAttempt.delivery_id == delivery_id,
                AddressProofAttempt.account_user_id == principal.user_id,
                AddressProofAttempt.session_id == principal.session_id,
                AddressProofAttempt.state == "VERIFIED",
            )
        )
        if attempt is None:
            return _problem("ADDRESS_PROOF_REQUIRED", 400)
        challenge, sealed, challenge_raw = claim_service.claim_options(
            db,
            delivery_id=delivery_id,
            account_user_id=principal.user_id,
            session_id=principal.session_id,
            attempt_id=attempt.id,
            now=datetime.now(timezone.utc),
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {
        "challenge_id": str(challenge.id),
        "expires_at": int(challenge.expires_at.timestamp()),
        "hpke_challenge_b64": base64.b64encode(sealed).decode(),
        "claim_challenge_b64": base64.b64encode(challenge_raw).decode(),
    }


@router.post("/v2/pending-deliveries/{delivery_id}/claim")
def submit_claim(
    delivery_id: uuid.UUID, payload: ClaimRequest, request: Request,
    db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_claims_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        claim = claim_service.execute_claim(
            db,
            delivery_id=delivery_id,
            intent_raw=_b64decode(payload.claim_intent_b64, max_bytes=4096),
            intent_signature_69=_b64decode(payload.claim_intent_signature_69_b64, max_bytes=128),
            operation_id=payload.idempotency_key,
            principal_account_id=principal.user_id,
            principal_session_id=principal.session_id,
            vault=ctx["vault"],
            service_signer=ctx["service_signer"],
            service_key_id=ctx["service_key_id"],
            alias_evidence_key=ctx["hmac_key"],
            alias_evidence_method="otp",
            proof_mac_key=ctx["proof_mac_key"],
            now=datetime.now(timezone.utc),
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {
        "claim_id": str(claim.claim_id),
        "state": "CLAIMED",
        "claim_binding_b64": base64.b64encode(bytes(claim.claim_binding)).decode(),
        "claim_signature_69_b64": base64.b64encode(bytes(claim.claim_signature)).decode(),
        "envelope_b64": base64.b64encode(bytes(claim.envelope)).decode(),
        "envelope_sha256_hex": bytes(claim.envelope_sha256).hex(),
    }


@router.get("/v2/pending-deliveries/{delivery_id}/claim")
def fetch_claim(
    delivery_id: uuid.UUID, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        claim = claim_service.get_claim(
            db, delivery_id=delivery_id, account_user_id=principal.user_id
        )
    except Exception as exc:
        return _map_error(exc)
    return {
        "claim_id": str(claim.claim_id),
        "state": "CLAIMED",
        "claim_binding_b64": base64.b64encode(bytes(claim.claim_binding)).decode(),
        "claim_signature_69_b64": base64.b64encode(bytes(claim.claim_signature)).decode(),
        "envelope_b64": base64.b64encode(bytes(claim.envelope)).decode(),
        "envelope_sha256_hex": bytes(claim.envelope_sha256).hex(),
    }


@router.post("/v2/pending-deliveries/{delivery_id}/ack")
def ack_delivery_claim(
    delivery_id: uuid.UUID, payload: AckRequest, request: Request,
    db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        ok = claim_service.ack_claim(
            db,
            delivery_id=delivery_id,
            account_user_id=principal.user_id,
            claim_sha256=bytes.fromhex(payload.claim_sha256_hex),
            envelope_sha256=bytes.fromhex(payload.envelope_sha256_hex),
        )
    except Exception as exc:
        return _map_error(exc)
    return {"acknowledged": ok}


@router.post("/v2/gift-lookups/{reference}/continue")
def lookup_continue(
    reference: uuid.UUID, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    """Authenticated continuation (frozen R7 DTO).

    - Owner -> {"next":"sync","capsule_id"}: ordinary sync/scan.
    - Genuinely unclaimed pending ->
      {"next":"proof","gift_reference","delivery_id","capsule_id",
       "delivery_target_id","channel","claim_expires_at",
       "publish_statement_b64","publish_signature_69_b64"}: everything
      the client needs for R8 (delivery_id), R10/R11 (S snapshot carries
      D/KC/expiry/service-key; H is its hash). S is sender-signed public
      verification material — no plaintext, keys, or destination.
    - Foreign already-owned (v1 recipient or another account's claim) ->
      generic GIFT_UNAVAILABLE, never a proof branch for someone else.
    The reference alone authorizes nothing."""
    from sqlalchemy import select as _select

    from remanence.capsules.models import Capsule
    from remanence.pending.claim_models import PendingDelivery
    from remanence.pending.models import GiftLocator

    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    locator = db.get(GiftLocator, reference)
    if locator is None or locator.state != "ACTIVE":
        return _problem("GIFT_UNAVAILABLE", 404)
    owner_hash = retire_service.owner_publication_hash(
        db, capsule_id=locator.capsule_id, account_user_id=principal.user_id
    )
    if owner_hash is not None:
        return {"next": "sync", "capsule_id": str(locator.capsule_id)}
    delivery = db.scalar(
        _select(PendingDelivery).where(PendingDelivery.capsule_id == locator.capsule_id)
    )
    if delivery is not None:
        if delivery.state == "PENDING":
            if (
                delivery.publish_statement is None
                or delivery.publish_signature is None
            ):
                return _problem("GIFT_UNAVAILABLE", 404)
            return {
                "next": "proof",
                "gift_reference": str(locator.id),
                "delivery_id": str(delivery.id),
                "capsule_id": str(delivery.capsule_id),
                "delivery_target_id": str(delivery.delivery_target_id),
                "channel": delivery.channel,
                "claim_expires_at": int(delivery.claim_expires_at.timestamp()),
                "publish_statement_b64": base64.b64encode(
                    bytes(delivery.publish_statement)
                ).decode(),
                "publish_signature_69_b64": base64.b64encode(
                    bytes(delivery.publish_signature)
                ).decode(),
            }
        return _problem("GIFT_UNAVAILABLE", 404)
    if db.get(Capsule, locator.capsule_id) is not None:
        # v1 capsule owned by someone else (owner branch above missed).
        return _problem("GIFT_UNAVAILABLE", 404)
    return _problem("GIFT_UNAVAILABLE", 404)


@router.post("/v2/gift-lookups")
def gift_lookup(payload: GiftLookupRequest, request: Request, db: Session = Depends(get_db_session)):
    import time as _time

    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    client_host = request.client.host if request.client is not None else "unknown"
    if not _lookup_limiter(request).check(client_host, now_ts=_time.time()):
        return _problem("RATE_LIMITED", 429)
    try:
        canonical = normalize_code(payload.code)
    except Exception:
        return _problem("GIFT_UNAVAILABLE", 404)
    from remanence.pending.locator_store import code_hmac as _hmac

    row = locator_store.resolve_active(db, tag=_hmac(canonical, ctx["hmac_key"]))
    if row is None:
        return _problem("GIFT_UNAVAILABLE", 404)
    return {"gift_reference": str(row.id), "channel": "unknown", "hint": row.hint_mask}


@router.post("/v2/me/handle")
def set_handle(
    payload: HandleRequest, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        user = profile_service.set_first_handle(
            db, user_id=principal.user_id, handle=payload.handle
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    return {"user_id": str(user.id), "handle": user.handle_normalized}


@router.post("/v2/capsules/{capsule_id}/locator-open-completion")
def locator_completion(
    capsule_id: uuid.UUID, payload: LocatorCompletionRequest, request: Request,
    db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    """Frozen R15: request {publication_sha256_hex, operation_id};
    response {capsule_id, completion:true, locator_state:'RETIRED_OPEN'}.
    Admission is read from the DB; no client readiness fields."""
    ctx = _gate(request, "pending_claims_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    try:
        pubhash = bytes.fromhex(payload.publication_sha256_hex)
    except ValueError:
        return _problem("GIFT_UNAVAILABLE", 404)
    try:
        from remanence.pending.models import GiftLocator

        locator = db.scalar(select(GiftLocator).where(GiftLocator.capsule_id == capsule_id))
        completion = retire_service.complete_locator_open(
            db,
            capsule_id=capsule_id,
            account_user_id=principal.user_id,
            publication_sha256=pubhash,
            locator_id=locator.id if locator is not None else None,
            now=datetime.now(timezone.utc),
        )
        db.commit()
    except Exception as exc:
        db.rollback()
        return _map_error(exc)
    _ = completion
    return {"capsule_id": str(capsule_id), "completion": True, "locator_state": "RETIRED_OPEN"}


@router.get("/v2/capsules/{capsule_id}/locator-open-completion")
def locator_completion_get(
    capsule_id: uuid.UUID, request: Request, db: Session = Depends(get_db_session),
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
):
    ctx = _gate(request, "pending_protocol_enabled")
    if isinstance(ctx, JSONResponse):
        return ctx
    row = retire_service.get_completion(
        db, capsule_id=capsule_id, account_user_id=principal.user_id
    )
    if row is None:
        return _problem("GIFT_UNAVAILABLE", 404)
    return {"capsule_id": str(capsule_id), "completion": True, "locator_state": "RETIRED_OPEN"}
