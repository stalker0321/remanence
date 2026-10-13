"""Pending publication: draft + finalize (T185 M3). No routes mounted.

Finalize atomically: verified sender S + sealed escrow + ACTIVE locator.
v1 checks are never bypassed: separate verifiers, no fallback between
versions. Backend failures roll back with no partial publication.
"""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime
from typing import BinaryIO

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.pending import domains
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.locator_store import activate, allocate_reserved
from remanence.pending.models import GiftLocator
from remanence.pending.routing import check_finalize_route
from remanence.pending.vault import (
    EscrowVault,
    SealedHandle,
    VaultError,
    build_escrow_aad,
    verify_escrow_bindings,
)
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

MAX_CLAIM_DEADLINE_DAYS = 180


class PublicationError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("pending publication failed")
        self.code = code


def verify_signature_69(public_keyset: bytes, domain_input: bytes, sig: bytes) -> None:
    """Strict single ENABLED Ed25519 TINK public key verification:
    canonical keyset bytes (unknown fields rejected), canonical inner
    key, exact 69-byte TINK framing."""
    import tink
    from tink import signature
    from tink.proto import ed25519_pb2, tink_pb2

    if len(sig) != 69:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    try:
        keyset = tink_pb2.Keyset.FromString(public_keyset)
    except Exception as exc:
        raise PublicationError("PROTOCOL_UNSUPPORTED") from exc
    probe = tink_pb2.Keyset()
    probe.CopyFrom(keyset)
    probe.DiscardUnknownFields()
    if keyset.SerializeToString(deterministic=True) != bytes(public_keyset):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if probe.SerializeToString(deterministic=True) != bytes(public_keyset):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if len(keyset.key) != 1:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    key = keyset.key[0]
    if key.key_id == 0 or keyset.primary_key_id != key.key_id:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if key.output_prefix_type != tink_pb2.TINK:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if (
        key.status != tink_pb2.ENABLED
        or key.key_data.key_material_type != tink_pb2.KeyData.ASYMMETRIC_PUBLIC
        or key.key_data.type_url != "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"
    ):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    try:
        inner = ed25519_pb2.Ed25519PublicKey.FromString(key.key_data.value)
    except Exception as exc:
        raise PublicationError("PROTOCOL_UNSUPPORTED") from exc
    if len(inner.key_value) != 32 or inner.version != 0:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    inner_probe = ed25519_pb2.Ed25519PublicKey()
    inner_probe.CopyFrom(inner)
    inner_probe.DiscardUnknownFields()
    if inner_probe.SerializeToString() != key.key_data.value:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    try:
        verifier = tink.KeysetHandle.read_no_secret(
            tink.BinaryKeysetReader(public_keyset)
        ).primitive(signature.PublicKeyVerify)
        verifier.verify(sig, domain_input)
    except Exception as exc:
        raise PublicationError("PROTOCOL_UNSUPPORTED") from exc


def parse_statement(raw: bytes) -> dv2.PendingPublishStatementV2:
    from remanence.pending.canonical import CanonicalError, parse_strict

    try:
        statement = parse_strict(dv2.PendingPublishStatementV2, raw)
    except CanonicalError as exc:
        raise PublicationError("PROTOCOL_UNSUPPORTED") from exc
    if statement.protocol_version != 2 or statement.claim_policy_version != 1:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    for value in (
        statement.capsule_id,
        statement.sender_user_id,
        statement.sender_key_bundle_id,
        statement.delivery_target_id,
        statement.claim_signing_key_id,
    ):
        if len(value) != 16:
            raise PublicationError("PROTOCOL_UNSUPPORTED")
    for value in (statement.address_commitment, statement.key_commitment):
        if len(value) != 32:
            raise PublicationError("PROTOCOL_UNSUPPORTED")
    if statement.created_at_epoch_seconds < 0 or statement.claim_expires_at_epoch_seconds <= 0:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if statement.claim_expires_at_epoch_seconds <= statement.created_at_epoch_seconds:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if not statement.artifacts:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    order = [(a.kind, a.ordinal, bytes(a.blob_id)) for a in statement.artifacts]
    if order != sorted(order):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    for artifact in statement.artifacts:
        if len(bytes(artifact.blob_id)) != 16 or len(bytes(artifact.ciphertext_sha256)) != 32:
            raise PublicationError("PROTOCOL_UNSUPPORTED")
    return statement


def validate_declarations(declarations: list) -> list[dict]:
    """R3 shape validation: each entry needs blob_id UUID, kind 1..3,
    ordinal (-1 except PHOTO 0..4), non-negative size, 32-byte sha."""
    if not isinstance(declarations, list) or len(declarations) > 8:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    cleaned: list[dict] = []
    for entry in declarations:
        if not isinstance(entry, dict):
            raise PublicationError("PROTOCOL_UNSUPPORTED")
        try:
            blob_id = uuid.UUID(str(entry["blob_id"]))
            kind = int(entry["kind"])
            ordinal = int(entry["ordinal"])
            size = int(entry["ciphertext_size"])
            sha = bytes.fromhex(str(entry["ciphertext_sha256_hex"]))
        except Exception as exc:
            raise PublicationError("PROTOCOL_UNSUPPORTED") from exc
        if kind not in (1, 2, 3) or len(sha) != 32 or size < 0:
            raise PublicationError("PROTOCOL_UNSUPPORTED")
        if kind != 3 and ordinal != -1:
            raise PublicationError("PROTOCOL_UNSUPPORTED")
        if kind == 3 and not (0 <= ordinal <= 4):
            raise PublicationError("PROTOCOL_UNSUPPORTED")
        cleaned.append(
            {
                "blob_id": str(blob_id),
                "kind": kind,
                "ordinal": ordinal,
                "ciphertext_size": size,
                "ciphertext_sha256_hex": sha.hex(),
            }
        )
    order = [(e["kind"], e["ordinal"], e["blob_id"]) for e in cleaned]
    if order != sorted(order):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    return cleaned


def declarations_match_s(declarations: list | None, statement) -> bool:
    """Finalize binding: S artifacts must equal the R3 declarations."""
    if not declarations:
        return False
    if len(declarations) != len(statement.artifacts):
        return False
    for declared, artifact in zip(
        sorted(declarations, key=lambda e: (e["kind"], e["ordinal"], e["blob_id"])),
        sorted(statement.artifacts, key=lambda a: (a.kind, a.ordinal, bytes(a.blob_id))),
    ):
        if (
            declared["blob_id"] != str(uuid.UUID(bytes=bytes(artifact.blob_id)))
            or declared["kind"] != artifact.kind
            or declared["ordinal"] != artifact.ordinal
            or declared["ciphertext_size"] != artifact.ciphertext_size
            or declared["ciphertext_sha256_hex"] != bytes(artifact.ciphertext_sha256).hex()
        ):
            return False
    return True


def create_draft(
    session: Session,
    *,
    sender_user_id: uuid.UUID,
    capsule_id: uuid.UUID,
    delivery_target_id: uuid.UUID,
    route_id: uuid.UUID | None,
    channel: str,
    claim_expires_at: datetime,
    declarations: list | None,
    now: datetime,
    pending_sends_enabled: bool,
) -> PendingDelivery:
    if not pending_sends_enabled:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if channel not in ("email", "sms"):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if claim_expires_at <= now:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if (claim_expires_at - now).days > MAX_CLAIM_DEADLINE_DAYS:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    cleaned = validate_declarations(declarations or [])
    # Service-boundary freeze is mandatory on the pending path: every
    # draft rides a frozen route (sender/capsule/mode/target/expiry
    # checked). route_id=None is rejected here; harnesses needing a
    # routeless model use routing.decide_for_send + freeze_route first.
    from remanence.pending.routing import check_finalize_route as _check_route

    if route_id is None:
        raise PublicationError("ROUTE_STALE")
    route = _check_route(
        session,
        route_id=route_id,
        sender_user_id=sender_user_id,
        capsule_id=capsule_id,
        mode="PENDING_V2",
        now=now,
    )
    if route.delivery_target_id != delivery_target_id:
        raise PublicationError("ROUTE_STALE")
    row = PendingDelivery(
        id=uuid.uuid4(),
        sender_user_id=sender_user_id,
        capsule_id=capsule_id,
        delivery_target_id=delivery_target_id,
        route_id=route_id,
        channel=channel,
        state="DRAFT",
        claim_expires_at=claim_expires_at,
        blob_declarations=cleaned,
    )
    session.add(row)
    session.flush()
    return row


def _sender_bundle_or_raise(session: Session, sender_id: uuid.UUID) -> UserKeyBundle:
    sender = session.get(User, sender_id)
    if sender is None or sender.disabled_at is not None:
        raise PublicationError("ADDRESS_UNAVAILABLE")
    bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == sender_id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    if bundle is None:
        raise PublicationError("RECIPIENT_KEY_STALE")
    return bundle


def finalize_delivery(
    session: Session,
    *,
    delivery_id: uuid.UUID,
    principal_user_id: uuid.UUID,
    statement_raw: bytes,
    signature_69: bytes,
    escrow_payload_raw: bytes,
    vault: EscrowVault,
    expected_service_key_id: uuid.UUID,
    locator_hmac_key: bytes,
    locator_code_key_32: bytes,
    locator_code_key_mode: str,
    hint_mask: str,
    now: datetime,
    blob_exists=None,
    operation_id: uuid.UUID | None = None,
) -> tuple[PendingDelivery, str]:
    """Finalize a DRAFT into PENDING + ACTIVE locator in one flush.

    Sender ownership is fenced FIRST (delivery.sender vs the
    authenticated principal) — before S parsing, signature checks, and
    any escrow/K intake. Canonical-request replay returns the identical
    committed code (the sealed printable code is decrypted for the
    sender replay).
    """
    from remanence.pending.vault import aead_from_raw_key

    delivery = session.get(PendingDelivery, delivery_id, with_for_update=True)
    if delivery is None:
        raise PublicationError("GIFT_UNAVAILABLE")
    if delivery.sender_user_id != principal_user_id:
        raise PublicationError("GIFT_UNAVAILABLE")
    statement = parse_statement(statement_raw)
    statement_hash = hashlib.sha256(statement_raw).digest()
    request_digest = hashlib.sha256(
        b"v1|finalize|"
        + (operation_id.bytes if operation_id is not None else b"")
        + statement_raw
        + signature_69
        + hashlib.sha256(escrow_payload_raw).digest()
    ).digest()
    if delivery.state == "PENDING":
        # Exact full binding: same operation id AND same statement,
        # signature, and escrow bytes. A changed immutable S, a changed
        # signature, changed escrow, or a new operation id conflicts
        # with 409 (never 404, never a replay).
        if bytes(delivery.publish_statement) != statement_raw:
            raise PublicationError("IDEMPOTENCY_CONFLICT")
        if (
            operation_id is None
            or delivery.finalize_operation_id != operation_id
            or bytes(delivery.finalize_request_digest or b"") != request_digest
        ):
            raise PublicationError("IDEMPOTENCY_CONFLICT")
        locator = session.get(GiftLocator, delivery.locator_id) if delivery.locator_id else None
        code = ""
        if locator is not None and locator.code_ciphertext is not None:
            if locator_code_key_mode in ("prod", "beta"):
                raise PublicationError("PROTOCOL_UNSUPPORTED")
            primitive = aead_from_raw_key(locator_code_key_32)
            code = primitive.decrypt(
                bytes(locator.code_ciphertext), b"pending-locator-code-v1"
            ).decode("utf-8")
        return delivery, code
    if delivery.state != "DRAFT":
        raise PublicationError("GIFT_UNAVAILABLE")
    if (
        uuid.UUID(bytes=bytes(statement.capsule_id)) != delivery.capsule_id
        or uuid.UUID(bytes=bytes(statement.delivery_target_id)) != delivery.delivery_target_id
        or uuid.UUID(bytes=bytes(statement.sender_user_id)) != delivery.sender_user_id
    ):
        raise PublicationError("ROUTE_STALE")
    if uuid.UUID(bytes=bytes(statement.claim_signing_key_id)) != expected_service_key_id:
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if delivery.route_id is not None:
        check_finalize_route(
            session,
            route_id=delivery.route_id,
            sender_user_id=delivery.sender_user_id,
            capsule_id=delivery.capsule_id,
            mode="PENDING_V2",
            now=now,
        )
    bundle = _sender_bundle_or_raise(session, delivery.sender_user_id)
    if uuid.UUID(bytes=bytes(statement.sender_key_bundle_id)) != bundle.id:
        raise PublicationError("ROUTE_STALE")
    if not declarations_match_s(delivery.blob_declarations, statement):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    if blob_exists is not None:
        for artifact in statement.artifacts:
            if not blob_exists(uuid.UUID(bytes=bytes(artifact.blob_id))):
                raise PublicationError("PROTOCOL_UNSUPPORTED")
    verify_signature_69(
        bytes(bundle.signing_public_keyset),
        domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw,
        signature_69,
    )
    from remanence.pending.canonical import CanonicalError as _CanonicalError
    from remanence.pending.canonical import parse_strict as _parse_strict

    try:
        escrow_payload = _parse_strict(dv2.PendingEscrowPlaintextV1, escrow_payload_raw)
    except _CanonicalError as exc:
        raise PublicationError("PROTOCOL_UNSUPPORTED") from exc
    if (
        bytes(escrow_payload.publish_statement_sha256) != statement_hash
        or bytes(escrow_payload.address_commitment) != bytes(statement.address_commitment)
        or bytes(escrow_payload.key_commitment) != bytes(statement.key_commitment)
        or uuid.UUID(bytes=bytes(escrow_payload.capsule_id)) != delivery.capsule_id
        or uuid.UUID(bytes=bytes(escrow_payload.delivery_target_id)) != delivery.delivery_target_id
        or uuid.UUID(bytes=bytes(escrow_payload.sender_user_id)) != delivery.sender_user_id
        or escrow_payload.claim_expires_at_epoch_seconds
        != statement.claim_expires_at_epoch_seconds
    ):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    aad = build_escrow_aad(
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        sender_user_id=delivery.sender_user_id.bytes,
        publish_statement_sha256=statement_hash,
        address_commitment=bytes(statement.address_commitment),
        key_commitment=bytes(statement.key_commitment),
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds,
    )
    try:
        handle = vault.seal(escrow_payload, aad)
    except VaultError as exc:
        raise PublicationError("PROVIDER_UNAVAILABLE") from exc
    locator, code = allocate_reserved(
        session, capsule_id=delivery.capsule_id, hint_mask=hint_mask, hmac_key=locator_hmac_key
    )
    if locator_code_key_mode in ("prod", "beta"):
        raise PublicationError("PROTOCOL_UNSUPPORTED")
    code_primitive = aead_from_raw_key(locator_code_key_32)
    activate(
        session,
        locator_id=locator.id,
        code_ciphertext=code_primitive.encrypt(code.encode("ascii"), b"pending-locator-code-v1"),
    )
    delivery.publish_statement = statement_raw
    delivery.publish_statement_sha256 = statement_hash
    delivery.publish_signature = signature_69
    delivery.address_commitment = bytes(statement.address_commitment)
    delivery.key_commitment = bytes(statement.key_commitment)
    delivery.service_key_id = expected_service_key_id
    delivery.vault_name = handle.vault
    delivery.vault_key_id = handle.key_id
    delivery.vault_ciphertext = handle.ciphertext
    delivery.vault_binding_hex = handle.binding_digest_hex
    delivery.locator_id = locator.id
    delivery.finalize_operation_id = operation_id
    delivery.finalize_request_digest = request_digest
    delivery.state = "PENDING"
    session.flush()
    return delivery, code


def cancel_delivery(session: Session, *, delivery_id: uuid.UUID, sender_user_id: uuid.UUID) -> None:
    """Sender cancel before claim. Claimed gifts cannot be unclaimed here
    (v1 keeps its own first-open/window semantics)."""
    from remanence.pending.locator_store import retire as retire_locator

    delivery = session.get(PendingDelivery, delivery_id, with_for_update=True)
    if delivery is None or delivery.sender_user_id != sender_user_id:
        raise PublicationError("GIFT_UNAVAILABLE")
    if delivery.state == "CANCELLED":
        return
    if delivery.state != "DRAFT" and delivery.state != "PENDING":
        raise PublicationError("CLAIM_CONFLICT")
    delivery.state = "CANCELLED"
    if delivery.locator_id is not None:
        retire_locator(session, locator_id=delivery.locator_id, terminal=True)
    session.flush()
