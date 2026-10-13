"""Atomic durable claim (T185 M3). Commit before any exposure.

Transaction outline (bounded, synchronous; KMS work bounded by the vault
adapter, whole-operation retry on rollback):
address-tag lock -> account row -> active bundle row -> delivery row ->
attempt/challenge rows. UNIQUE(delivery_target_id) is the second
concurrency guard. Escrow is removed in the SAME commit that persists
the recipient envelope; replay returns exact stored bytes.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from remanence.pending import domains
from remanence.pending.claim_models import (
    AddressProofAttempt,
    ClaimChallenge,
    PendingClaim,
    PendingDelivery,
)
from remanence.pending.proof import ProofError
from remanence.pending.publication import verify_signature_69
from remanence.pending.registry import advisory_key
from remanence.pending.vault import EscrowVault, SealedHandle, VaultError, build_escrow_aad
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

CHALLENGE_TTL = timedelta(minutes=5)


class ClaimError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("pending claim failed")
        self.code = code


def _locked_delivery(session: Session, delivery_id: uuid.UUID) -> PendingDelivery | None:
    """Row-locked fresh read that bypasses the identity map
    (populate_existing): a session that touched the delivery earlier in
    its transaction must NOT reuse a stale cached copy for the
    terminal-state gate — otherwise cancel-vs-claim races double-win."""
    return session.scalar(
        select(PendingDelivery)
        .where(PendingDelivery.id == delivery_id)
        .with_for_update()
        .execution_options(populate_existing=True),
    )


def _delivery_or_raise(session: Session, delivery_id: uuid.UUID, now: datetime) -> PendingDelivery:
    delivery = _locked_delivery(session, delivery_id)
    if delivery is None or delivery.state != "PENDING":
        raise ClaimError("GIFT_UNAVAILABLE")
    if delivery.claim_expires_at <= now:
        raise ClaimError("PROOF_EXPIRED")
    return delivery


def parse_claim_intent(raw: bytes) -> dv2.ClaimIntentV2:
    """Strict canonical parser: raw-byte equality plus unknown-field,
    version, and size checks before any authorization decision (same bar
    as S parsing)."""
    from remanence.pending.canonical import CanonicalError, parse_strict

    try:
        intent = parse_strict(dv2.ClaimIntentV2, raw)
    except CanonicalError as exc:
        raise ClaimError("PROTOCOL_UNSUPPORTED") from exc
    if intent.intent_version != 1:
        raise ClaimError("PROTOCOL_UNSUPPORTED")
    for value in (
        intent.operation_id,
        intent.capsule_id,
        intent.delivery_target_id,
        intent.proof_id,
        intent.challenge_id,
        intent.recipient_user_id,
        intent.recipient_key_bundle_id,
        intent.session_id,
    ):
        if len(value) != 16:
            raise ClaimError("PROTOCOL_UNSUPPORTED")
    for value in (intent.publish_statement_sha256, intent.nonce, intent.nonce_sha256):
        if len(bytes(value)) != 32:
            raise ClaimError("PROTOCOL_UNSUPPORTED")
    return intent


def claim_options(
    session: Session,
    *,
    delivery_id: uuid.UUID,
    account_user_id: uuid.UUID,
    session_id: uuid.UUID,
    attempt_id: uuid.UUID,
    now: datetime,
) -> tuple[ClaimChallenge, bytes, bytes]:
    """Verified attempt + current ACTIVE bundle -> bound HPKE nonce
    challenge. The nonce is sealed to the AUTHORITATIVE bundle bytes from
    the directory — never caller-supplied key material, so a substituted
    adversary key cannot bypass HPKE possession. Returns (challenge row,
    sealed nonce, canonical challenge context bytes).

    Coordination amendment (frozen §8 R10): the canonical context bytes
    are returned to the client as claim_challenge_b64 because the HPKE
    context (nonce hash, session UUID, …) is unknowable before decrypting
    the nonce. The client validates the known fields (capsule/target/
    account/bundle/expiry) against these exact bytes, then uses them
    verbatim as the HPKE info. Only the nonce hash is stored server-side;
    returning the hash alone never suffices as possession. Unified lock
    order: delivery -> account -> bundle -> attempt."""
    import tink
    from tink import hybrid

    # Same lane serialization as execute_claim (claim-advisory first).
    if session.bind is not None and session.bind.dialect.name == "postgresql":
        session.connection().exec_driver_sql(
            f"SELECT pg_advisory_xact_lock({advisory_key('claim', delivery_id.bytes)})"
        )
    delivery = _delivery_or_raise(session, delivery_id, now)
    account = session.get(User, account_user_id, with_for_update=True)
    if account is None or account.disabled_at is not None:
        raise ClaimError("ADDRESS_UNAVAILABLE")
    bundle = session.scalar(
        select(UserKeyBundle)
        .where(
            UserKeyBundle.user_id == account_user_id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
        .with_for_update(),
    )
    if bundle is None:
        raise ClaimError("RECIPIENT_KEY_STALE")
    attempt = session.get(AddressProofAttempt, attempt_id, with_for_update=True)
    if (
        attempt is None
        or attempt.delivery_id != delivery.id
        or attempt.account_user_id != account_user_id
        or attempt.session_id != session_id
        or attempt.state != "VERIFIED"
        or attempt.expires_at <= now
    ):
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    try:
        public_handle = tink.KeysetHandle.read_no_secret(
            tink.BinaryKeysetReader(bytes(bundle.encryption_public_keyset))
        )
        enc = public_handle.primitive(hybrid.HybridEncrypt)
    except Exception as exc:
        raise ClaimError("RECIPIENT_KEY_STALE") from exc
    nonce = secrets.token_bytes(32)
    challenge_id = uuid.uuid4()
    context = dv2.ClaimChallengeV1(
        challenge_id=challenge_id.bytes,
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        recipient_user_id=account_user_id.bytes,
        recipient_key_bundle_id=bundle.id.bytes,
        session_id=session_id.bytes,
        nonce_sha256=hashlib.sha256(nonce).digest(),
        expires_at_epoch_seconds=int((now + CHALLENGE_TTL).timestamp()),
    )
    context_raw = context.SerializeToString(deterministic=True)
    info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + context_raw
    try:
        sealed = enc.encrypt(nonce, info)
    except Exception as exc:
        raise ClaimError("RECIPIENT_KEY_STALE") from exc
    challenge = ClaimChallenge(
        id=challenge_id,
        delivery_id=delivery.id,
        account_user_id=account_user_id,
        bundle_id=bundle.id,
        session_id=session_id,
        nonce_sha256=hashlib.sha256(nonce).digest(),
        expires_at=now + CHALLENGE_TTL,
    )
    session.add(challenge)
    session.flush()
    return challenge, sealed, context_raw


def _request_digest(intent_raw: bytes, operation_id: uuid.UUID) -> bytes:
    return hashlib.sha256(b"v1|claim|" + operation_id.bytes + b"|" + intent_raw).digest()


def execute_claim(
    session: Session,
    *,
    delivery_id: uuid.UUID,
    intent_raw: bytes,
    intent_signature_69: bytes,
    operation_id: uuid.UUID,
    principal_account_id: uuid.UUID,
    principal_session_id: uuid.UUID,
    vault: EscrowVault,
    service_signer,
    service_key_id: uuid.UUID,
    alias_evidence_key: bytes,
    alias_evidence_method: str,
    proof_mac_key: bytes,
    now: datetime,
    clock=None,
) -> PendingClaim:
    """Full atomic claim. Authenticated principal (account/session from the
    server session layer — never the client intent) is checked
    independently, including on the cached replay path. Expiry is
    rechecked on a post-vault clock read (injectable for tests); the
    entry `now` is only the pre-work snapshot. Unified lock order:
    claim-target advisory -> delivery -> account -> bundle ->
    attempt/challenge (-> address lock for the alias bind).

    intent.bind_alias is honored, never ignored: with it set, the proven
    address is reserved+verified to the claimant under the address lock
    with a server evidence MAC. A foreign-owned reservation fails the
    claim (never transferred or merged). With it unset, no alias row is
    touched — the gift is still owned, the address stays unbound."""
    import tink

    intent = parse_claim_intent(intent_raw)
    # Lane serialization FIRST (unified order: claim-advisory ->
    # delivery -> account -> bundle -> attempt/challenge -> address
    # lock as a leaf inside the alias bind). Keyed on the server-route
    # delivery id (stable across options/execute calls for one gift);
    # the address lock nests later as a leaf — no path holds the address
    # lock while acquiring delivery/account rows, so no lock cycle.
    if session.bind is not None and session.bind.dialect.name == "postgresql":
        session.connection().exec_driver_sql(
            f"SELECT pg_advisory_xact_lock({advisory_key('claim', delivery_id.bytes)})"
        )
    delivery = _locked_delivery(session, delivery_id)
    if delivery is None:
        raise ClaimError("GIFT_UNAVAILABLE")
    if uuid.UUID(bytes=bytes(intent.capsule_id)) != delivery.capsule_id or uuid.UUID(
        bytes=bytes(intent.delivery_target_id)
    ) != delivery.delivery_target_id:
        raise ClaimError("CLAIM_CONFLICT")
    if bytes(intent.publish_statement_sha256) != bytes(delivery.publish_statement_sha256 or b""):
        raise ClaimError("CLAIM_CONFLICT")
    digest = _request_digest(intent_raw, operation_id)
    # Idempotent replay / conflict before any crypto work — reachable in
    # both PENDING (double-submit) and CLAIMED (lost-response retry).
    # The authenticated principal must own the stored result; a foreign
    # principal learns nothing beyond generic unavailability.
    existing = session.get(PendingClaim, delivery.delivery_target_id)
    if existing is not None:
        if existing.operation_id == operation_id and bytes(existing.request_digest) == digest:
            if existing.account_user_id != principal_account_id:
                raise ClaimError("GIFT_UNAVAILABLE")
            return existing
        raise ClaimError("IDEMPOTENCY_CONFLICT")
    if delivery.state != "PENDING" or delivery.claim_expires_at <= now:
        raise ClaimError("GIFT_UNAVAILABLE")
    account_id = uuid.UUID(bytes=bytes(intent.recipient_user_id))
    bundle_id = uuid.UUID(bytes=bytes(intent.recipient_key_bundle_id))
    session_id = uuid.UUID(bytes=bytes(intent.session_id))
    if account_id != principal_account_id or session_id != principal_session_id:
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    account = session.get(User, account_id, with_for_update=True)
    if account is None or account.disabled_at is not None:
        raise ClaimError("ADDRESS_UNAVAILABLE")
    from remanence.users.safety_models import UserBlock as _UserBlock

    if session.get(_UserBlock, (account_id, delivery.sender_user_id)) is not None:
        # Existing contact-block policy applies once the recipient is
        # known, mirroring v1 draft/incoming.
        raise ClaimError("ADDRESS_UNAVAILABLE")
    bundle = session.get(UserKeyBundle, bundle_id, with_for_update=True)
    if (
        bundle is None
        or bundle.user_id != account_id
        or bundle.status != KeyBundleStatus.ACTIVE
    ):
        raise ClaimError("RECIPIENT_KEY_STALE")
    attempt = session.get(
        AddressProofAttempt,
        uuid.UUID(bytes=bytes(intent.proof_id)),
        with_for_update=True,
    )
    if (
        attempt is None
        or attempt.delivery_id != delivery.id
        or attempt.account_user_id != account_id
        or attempt.session_id != session_id
        or attempt.state != "VERIFIED"
        or attempt.expires_at <= now
    ):
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    challenge = session.get(
        ClaimChallenge, uuid.UUID(bytes=bytes(intent.challenge_id)), with_for_update=True
    )
    if (
        challenge is None
        or challenge.delivery_id != delivery.id
        or challenge.account_user_id != account_id
        or challenge.bundle_id != bundle_id
        or challenge.session_id != session_id
        or challenge.used_at is not None
        or challenge.expires_at <= now
    ):
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    if not hmac.compare_digest(
        bytes(challenge.nonce_sha256), hashlib.sha256(bytes(intent.nonce)).digest()
    ):
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    if bytes(intent.nonce_sha256) != bytes(challenge.nonce_sha256):
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    # D14 adversarial reject BEFORE any vault work: the attempt's
    # outside-DB evidence MAC must re-verify (binds gift/account/
    # session/address/expiry/channel/state). A DB-forged VERIFIED row,
    # account/session swap, or expiry stretch dies here — escrow never
    # opens for it.
    from remanence.pending.proof import verify_attempt_mac

    if attempt.state != "VERIFIED" or not verify_attempt_mac(attempt, mac_key=proof_mac_key):
        raise ClaimError("ADDRESS_PROOF_REQUIRED")
    # Registry reservation constraint, enforced (not helper-only): a
    # foreign-owned or retired reservation fails the claim; a VERIFIED
    # alias with a broken evidence MAC fails too.
    from remanence.pending.registry import RegistryError, claim_fence

    try:
        claim_fence(
            session,
            kind=attempt.address_kind,
            tag=bytes(attempt.address_lookup_tag),
            owner_user_id=account_id,
            evidence_key=alias_evidence_key,
        )
    except RegistryError as exc:
        raise ClaimError("ADDRESS_UNAVAILABLE") from exc
    # bind_alias honored under the address lock: fresh proof binds the
    # proven address to the claimant; foreign reservations fail closed.
    if intent.bind_alias:
        from remanence.pending.registry import RegistryError, verify_by_tag

        try:
            verify_by_tag(
                session,
                kind=attempt.address_kind,
                tag=bytes(attempt.address_lookup_tag),
                owner_user_id=account_id,
                evidence_method=alias_evidence_method,
                evidence_key=alias_evidence_key,
                now=now,
            )
        except RegistryError as exc:
            raise ClaimError("ADDRESS_UNAVAILABLE") from exc
    # Account HPKE+signing possession via the existing account key.
    try:
        verify_signature_69(
            bytes(bundle.signing_public_keyset),
            domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw,
            intent_signature_69,
        )
    except Exception as exc:
        raise ClaimError("ADDRESS_PROOF_REQUIRED") from exc
    # Sender key still valid for new claims (revoked sender bundle ends issuance).
    sender_bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == delivery.sender_user_id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    if sender_bundle is None:
        raise ClaimError("ADDRESS_UNAVAILABLE")
    # Time recheck after bounded vault work happens below (proof/challenge/
    # gift expiry re-validated right before insert).
    try:
        statement = dv2.PendingPublishStatementV2.FromString(bytes(delivery.publish_statement))
    except Exception as exc:
        raise ClaimError("PROTOCOL_UNSUPPORTED") from exc
    aad = build_escrow_aad(
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        sender_user_id=delivery.sender_user_id.bytes,
        publish_statement_sha256=bytes(delivery.publish_statement_sha256),
        address_commitment=bytes(delivery.address_commitment),
        key_commitment=bytes(delivery.key_commitment),
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds,
    )
    handle = SealedHandle(
        vault=delivery.vault_name or "",
        key_id=delivery.vault_key_id or "",
        ciphertext=bytes(delivery.vault_ciphertext or b""),
        binding_digest_hex=delivery.vault_binding_hex or "",
    )
    try:
        payload = vault.open(handle, aad)
    except VaultError as exc:
        raise ClaimError("PROVIDER_UNAVAILABLE") from exc
    # Expiry recheck on a POST-vault clock read: a slow unwrap may cross
    # proof/challenge/gift expiry. Production default is a fresh wall
    # read (never the pre-work snapshot); tests inject a bounded fake
    # clock for synthetic time.
    from datetime import timezone as _timezone

    post_now = clock() if clock is not None else datetime.now(_timezone.utc)
    if attempt.expires_at <= post_now or challenge.expires_at <= post_now or delivery.claim_expires_at <= post_now:
        raise ClaimError("PROOF_EXPIRED")
    claim_id = uuid.uuid4()
    binding = dv2.ClaimBindingV1(
        binding_version=1,
        capsule_protocol_version=2,
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        publish_statement_sha256=bytes(delivery.publish_statement_sha256),
        address_commitment=bytes(delivery.address_commitment),
        recipient_user_id=account_id.bytes,
        recipient_key_bundle_id=bundle_id.bytes,
        recipient_encryption_public_keyset_sha256=hashlib.sha256(
            bytes(bundle.encryption_public_keyset)
        ).digest(),
        claim_id=claim_id.bytes,
        claimed_at_epoch_seconds=int(post_now.timestamp()),
        claim_policy_version=1,
        claim_signing_key_id=service_key_id.bytes,
    )
    binding_raw = binding.SerializeToString(deterministic=True)
    binding_hash = hashlib.sha256(binding_raw).digest()
    try:
        binding_sig = service_signer.sign(
            domains.domain_prefix(domains.CLAIM_BINDING_DOMAIN) + binding_raw
        )
    except Exception as exc:
        raise ClaimError("PROVIDER_UNAVAILABLE") from exc
    if len(binding_sig) != 69:
        raise ClaimError("PROVIDER_UNAVAILABLE")
    context = dv2.ClaimedEnvelopeContextV2(
        protocol_version=2,
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        sender_user_id=delivery.sender_user_id.bytes,
        recipient_user_id=account_id.bytes,
        recipient_key_bundle_id=bundle_id.bytes,
        publish_statement_sha256=bytes(delivery.publish_statement_sha256),
        claim_binding_sha256=binding_hash,
    )
    plaintext = dv2.ClaimedEnvelopePlaintextV2(
        binding=context,
        capsule_aead_keyset=bytes(payload.capsule_aead_keyset),
        address_opening=payload.address_opening,
    )
    try:
        recipient_public = tink.KeysetHandle.read_no_secret(
            tink.BinaryKeysetReader(bytes(bundle.encryption_public_keyset))
        )
        from tink import hybrid

        enc = recipient_public.primitive(hybrid.HybridEncrypt)
        envelope = enc.encrypt(
            plaintext.SerializeToString(deterministic=True),
            domains.domain_prefix(domains.CLAIMED_ENVELOPE_DOMAIN)
            + context.SerializeToString(deterministic=True),
        )
    except Exception as exc:
        raise ClaimError("RECIPIENT_KEY_STALE") from exc
    claim = PendingClaim(
        delivery_target_id=delivery.delivery_target_id,
        delivery_id=delivery.id,
        claim_id=claim_id,
        account_user_id=account_id,
        bundle_id=bundle_id,
        claim_binding=binding_raw,
        claim_signature=binding_sig,
        envelope=envelope,
        envelope_sha256=hashlib.sha256(envelope).digest(),
        operation_id=operation_id,
        request_digest=digest,
        claimed_at=post_now,
    )
    session.add(claim)
    try:
        # Savepoint only: losing the target race rolls back the INSERT,
        # never the caller's surrounding transaction. The winner's
        # envelope stands; the loser gets nothing, even with valid proof.
        with session.begin_nested():
            session.flush()
    except IntegrityError as exc:
        raise ClaimError("CLAIM_CONFLICT") from exc
    attempt.state = "CONSUMED"
    challenge.used_at = now
    superseded = session.scalars(
        select(AddressProofAttempt).where(
            AddressProofAttempt.delivery_id == delivery.id,
            AddressProofAttempt.id != attempt.id,
            AddressProofAttempt.state == "VERIFIED",
        )
    ).all()
    for other in superseded:
        other.state = "SUPERSEDED"
    delivery.state = "CLAIMED"
    delivery.vault_ciphertext = None
    delivery.vault_name = None
    delivery.vault_key_id = None
    delivery.vault_binding_hex = None
    _insert_incoming_availability(
        session,
        account_id=account_id,
        capsule_id=delivery.capsule_id,
        delivery_id=delivery.id,
        now=post_now,
    )
    session.flush()
    return claim


def _insert_incoming_availability(
    session: Session,
    *,
    account_id: uuid.UUID,
    capsule_id: uuid.UUID,
    delivery_id: uuid.UUID,
    now: datetime,
) -> None:
    """Durable v2 account incoming in the SAME commit: losing code, target
    ID AND the HTTP response still leaves full sync discovery (code stays
    ACTIVE; no OTP needed). Ordered by claim time in a v2-specific feed,
    so late claims cannot fall behind a v1 publish-time cursor."""
    from remanence.pending.claim_models import PendingIncoming

    session.add(
        PendingIncoming(
            id=uuid.uuid4(),
            recipient_user_id=account_id,
            capsule_id=capsule_id,
            delivery_id=delivery_id,
            available_at=now,
        )
    )


def get_claim(
    session: Session, *, delivery_id: uuid.UUID, account_user_id: uuid.UUID
) -> PendingClaim:
    """Final account re-download (after OTP/session renewal). Never another
    account: ownership was fixed to a user UUID at claim."""
    delivery = session.get(PendingDelivery, delivery_id)
    if delivery is None:
        raise ClaimError("GIFT_UNAVAILABLE")
    claim = session.get(PendingClaim, delivery.delivery_target_id)
    if claim is None or claim.account_user_id != account_user_id:
        raise ClaimError("GIFT_UNAVAILABLE")
    return claim


def ack_claim(
    session: Session,
    *,
    delivery_id: uuid.UUID,
    account_user_id: uuid.UUID,
    claim_sha256: bytes,
    envelope_sha256: bytes,
) -> bool:
    """Optional control receipt: names claim/envelope hashes only. Not an
    opening claim, not forwarded to the sender. Verification only."""
    claim = get_claim(session, delivery_id=delivery_id, account_user_id=account_user_id)
    return hmac.compare_digest(
        hashlib.sha256(bytes(claim.claim_binding)).digest(), bytes(claim_sha256)
    ) and hmac.compare_digest(bytes(claim.envelope_sha256), bytes(envelope_sha256))
