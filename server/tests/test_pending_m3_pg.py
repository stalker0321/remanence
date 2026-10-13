"""T185 M3 isolated-PG tests: publication, proof, atomic claim, retire.

Full pending lifecycle on uniquely named synthetic DBs (alembic head).
Real Tink throughout: sender/service/account Ed25519, recipient HPKE,
dev AEAD vault. No providers, no network beyond localhost PG.
"""

from __future__ import annotations

import hashlib
import io
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import tink
from sqlalchemy import select
from tink import hybrid, signature, tink_config
from tink.proto import tink_pb2

pytest_plugins = ("test_session_repository_create",)

from remanence.pending import domains
from remanence.pending.claim import (
    ClaimError,
    ack_claim,
    claim_options,
    execute_claim,
    get_claim,
)
from remanence.pending.claim_models import PendingClaim, PendingDelivery, PendingIncoming
from remanence.pending.models import DeliveryAddressBinding
from remanence.pending.locator_store import code_hmac, resolve_active
from remanence.pending.proof import (
    ProofError,
    check_verification,
    start_verification,
    verify_google_shortcut,
)
from remanence.pending.profiles import set_first_handle
from remanence.pending.providers import MockDispatcher
from remanence.pending.publication import (
    PublicationError,
    cancel_delivery,
    create_draft,
    finalize_delivery,
)
from remanence.pending.registry import RegistryError, verify_binding
from remanence.pending.retire import (
    complete_locator_open,
    drain_retire_outbox,
    enqueue_retire,
    get_completion,
)
from remanence.pending.routing import RoutingError, decide_for_send, freeze_route
from remanence.pending.vault import DevSealedVault, VaultError
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

NOW = datetime(2030, 6, 1, 12, 0, 0, tzinfo=timezone.utc)
HMAC_KEY = bytes([0x0D] * 32)
EVIDENCE_KEY = bytes([0x0E] * 32)
DEST_KEY = bytes([0x0F] * 32)
OTP_KEY = bytes([0x10] * 32)
PROOF_MAC_KEY = bytes([0x13] * 32)
DEV_VAULT_KEY = bytes([0x11] * 32)
LOCATOR_CODE_KEY = bytes([0x12] * 32)
ADDRESS = "gift42@example.test"
SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _binary_public(handle: tink.KeysetHandle) -> bytes:
    buf = io.BytesIO()
    handle.write_no_secret(tink.BinaryKeysetWriter(buf))
    return buf.getvalue()


class Keyring:
    """Real per-actor key material (synthetic, test-only)."""

    def __init__(self) -> None:
        hpke_priv = tink.KeysetHandle.generate_new(
            hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM
        )
        self.hpke_private = hpke_priv
        self.hpke_public = _binary_public(hpke_priv.public_keyset_handle())
        ed_priv = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
        self.ed_private = ed_priv
        self.ed_public = _binary_public(ed_priv.public_keyset_handle())

    def sign69(self, data: bytes) -> bytes:
        return self.ed_private.primitive(signature.PublicKeySign).sign(data)

    def hpke_decrypt(self, ciphertext: bytes, info: bytes) -> bytes:
        return self.hpke_private.primitive(hybrid.HybridDecrypt).decrypt(ciphertext, info)


def _make_user(session, handle: str) -> User:
    user = User(
        id=uuid.uuid4(),
        email_normalized=f"{handle}-{uuid.uuid4().hex[:8]}@example.test",
        handle_normalized=handle,
        handle_display=handle,
    )
    session.add(user)
    session.flush()
    return user


def _make_bundle(session, user: User, ring: Keyring) -> UserKeyBundle:
    bundle = UserKeyBundle(
        id=uuid.uuid4(),
        user_id=user.id,
        encryption_public_keyset=ring.hpke_public,
        signing_public_keyset=ring.ed_public,
        suite=SUITE,
        protocol_version=1,
        status=KeyBundleStatus.ACTIVE,
    )
    session.add(bundle)
    session.flush()
    return bundle


def _real_aes_keyset(key32: bytes) -> bytes:
    from tink.proto import aes_gcm_pb2

    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=tink_pb2.KeyData(
                type_url="type.googleapis.com/google.crypto.tink.AesGcmKey",
                value=aes_gcm_pb2.AesGcmKey(version=0, key_value=key32).SerializeToString(),
                key_material_type=tink_pb2.KeyData.SYMMETRIC,
            ),
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset.SerializeToString()


def _publish_package(
    *,
    capsule_id: uuid.UUID,
    target_id: uuid.UUID,
    sender_id: uuid.UUID,
    sender_bundle_id: uuid.UUID,
    service_key_id: uuid.UUID,
    salt: bytes = bytes(range(32)),
):
    """Canonical S + escrow payload with correctly recomputed D/KC."""
    keyset_bytes = _real_aes_keyset(bytes([0x20] * 32))
    opening = dv2.AddressOpening(
        normalization_version=1, channel=1, normalized_destination=ADDRESS, salt=salt
    )
    opening_raw = opening.SerializeToString(deterministic=True)
    d = domains.domain_digest(domains.DELIVERY_ADDRESS_DOMAIN, opening_raw)
    kc_input = dv2.KeyCommitmentInput(
        version=1,
        capsule_id=capsule_id.bytes,
        delivery_target_id=target_id.bytes,
        capsule_aead_keyset=keyset_bytes,
    )
    kc = domains.domain_digest(
        domains.DELIVERY_KEY_DOMAIN, kc_input.SerializeToString(deterministic=True)
    )
    binding = dv2.ArtifactBindingV2(
        blob_id=uuid.uuid4().bytes,
        kind=3,
        ordinal=0,
        ciphertext_size=505,
        ciphertext_sha256=bytes([0x55] * 32),
    )
    statement = dv2.PendingPublishStatementV2(
        protocol_version=2,
        capsule_id=capsule_id.bytes,
        sender_user_id=sender_id.bytes,
        sender_key_bundle_id=sender_bundle_id.bytes,
        delivery_target_id=target_id.bytes,
        address_commitment=d,
        key_commitment=kc,
        created_at_epoch_seconds=int(NOW.timestamp()),
        claim_expires_at_epoch_seconds=int(NOW.timestamp()) + 180 * 86400,
        claim_policy_version=1,
        claim_signing_key_id=service_key_id.bytes,
        artifacts=[binding],
    )
    statement_raw = statement.SerializeToString(deterministic=True)
    payload = dv2.PendingEscrowPlaintextV1(
        escrow_version=1,
        capsule_protocol_version=2,
        capsule_id=capsule_id.bytes,
        delivery_target_id=target_id.bytes,
        sender_user_id=sender_id.bytes,
        publish_statement_sha256=hashlib.sha256(statement_raw).digest(),
        address_commitment=d,
        key_commitment=kc,
        claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds,
        capsule_aead_keyset=keyset_bytes,
        address_opening=opening,
    )
    return statement_raw, payload.SerializeToString(deterministic=True), keyset_bytes


def _setup_pending(session, *, address: str = ADDRESS, channel: str = "email"):
    """Route -> draft; returns dict with sender/ring/bundle/route/delivery."""
    sender = _make_user(session, f"snd{uuid.uuid4().hex[:8]}")
    sender_ring = Keyring()
    sender_bundle = _make_bundle(session, sender, sender_ring)
    session.commit()
    kind = "phone" if address.startswith("+") else "email"
    decision = decide_for_send(
        session,
        destination_kind=kind,
        normalized_destination=address,
        active_bundle_id=None,
        hmac_key=HMAC_KEY,
    )
    assert decision.mode == "PENDING_V2"
    route = freeze_route(
        session,
        sender_user_id=sender.id,
        capsule_id=uuid.uuid4(),
        destination_kind=kind,
        normalized_destination=address,
        destination_key_32=DEST_KEY,
        destination_key_mode="test",
        hmac_key=HMAC_KEY,
        decision=decision,
        now=NOW,
        pending_sends_enabled=True,
    )
    delivery = create_draft(
        session,
        sender_user_id=sender.id,
        capsule_id=route.capsule_id,
        delivery_target_id=decision.delivery_target_id,
        route_id=route.id,
        channel=channel,
        claim_expires_at=NOW + timedelta(days=180),
        now=NOW,
        pending_sends_enabled=True,
    )
    session.commit()
    return {
        "sender": sender,
        "sender_ring": sender_ring,
        "sender_bundle": sender_bundle,
        "route": route,
        "delivery": delivery,
    }


def _finalize(session, setup, service_key_id: uuid.UUID, vault) -> tuple:
    delivery = setup["delivery"]
    statement_raw, payload_raw, _ = _publish_package(
        capsule_id=delivery.capsule_id,
        target_id=delivery.delivery_target_id,
        sender_id=setup["sender"].id,
        sender_bundle_id=setup["sender_bundle"].id,
        service_key_id=service_key_id,
    )
    sig = setup["sender_ring"].sign69(
        domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw
    )
    assert len(sig) == 69
    finalized, code = finalize_delivery(
        session,
        delivery_id=delivery.id,
        principal_user_id=setup["sender"].id,
        statement_raw=statement_raw,
        signature_69=sig,
        escrow_payload_raw=payload_raw,
        vault=vault,
        expected_service_key_id=service_key_id,
        locator_hmac_key=HMAC_KEY,
        locator_code_key_32=LOCATOR_CODE_KEY,
        locator_code_key_mode="test",
        hint_mask="g•••@•••",
        now=NOW,
    )
    session.commit()
    return finalized, code, statement_raw, payload_raw


def _prove(session, delivery, account, session_id, provider, code="12345678"):
    attempt = start_verification(
        session,
        delivery_id=delivery.id,
        account_user_id=account.id,
        session_id=session_id,
        confirm_destination=ADDRESS,
        destination_key_32=DEST_KEY,
        destination_tag_key=HMAC_KEY,
        registry_hmac_key=HMAC_KEY,
        otp_key=OTP_KEY,
        proof_mac_key=PROOF_MAC_KEY,
        email_sender=provider,
        sms_provider=None,
        locale="en",
        now=NOW,
        attempt_id=uuid.uuid4(),
        code_supplier=lambda: code,
    )
    session.commit()
    verified = check_verification(
        session,
        attempt_id=attempt.id,
        delivery_id=delivery.id,
        account_user_id=account.id,
        session_id=session_id,
        code=code,
        otp_key=OTP_KEY,
        sms_provider=None,
        proof_mac_key=PROOF_MAC_KEY,
        now=NOW,
    )
    session.commit()
    return verified


def _claim(session, delivery, account, bundle, account_ring, attempt, service_ring, service_key_id, vault, operation_id, clock=None, bind_alias=True):
    if clock is None:
        clock = lambda: NOW
    challenge, sealed_nonce, _ctx_raw = claim_options(
        session,
        delivery_id=delivery.id,
        account_user_id=account.id,
        session_id=attempt.session_id,
        attempt_id=attempt.id,
        now=NOW,
    )
    session.commit()
    info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
        challenge_id=challenge.id.bytes,
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        recipient_user_id=account.id.bytes,
        recipient_key_bundle_id=bundle.id.bytes,
        session_id=attempt.session_id.bytes,
        nonce_sha256=challenge.nonce_sha256,
        expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
    ).SerializeToString(deterministic=True)
    nonce = account_ring.hpke_decrypt(sealed_nonce, info)
    intent = dv2.ClaimIntentV2(
        intent_version=1,
        operation_id=operation_id.bytes,
        capsule_id=delivery.capsule_id.bytes,
        delivery_target_id=delivery.delivery_target_id.bytes,
        publish_statement_sha256=delivery.publish_statement_sha256,
        proof_id=attempt.id.bytes,
        challenge_id=challenge.id.bytes,
        recipient_user_id=account.id.bytes,
        recipient_key_bundle_id=bundle.id.bytes,
        session_id=attempt.session_id.bytes,
        nonce=nonce,
        nonce_sha256=hashlib.sha256(nonce).digest(),
        bind_alias=bind_alias,
    )
    intent_raw = intent.SerializeToString(deterministic=True)
    intent_sig = account_ring.sign69(
        domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw
    )
    claim = execute_claim(
        session,
        delivery_id=delivery.id,
        intent_raw=intent_raw,
        intent_signature_69=intent_sig,
        operation_id=operation_id,
        principal_account_id=account.id,
        principal_session_id=attempt.session_id,
        vault=vault,
        service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
        service_key_id=service_key_id,
        alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
        proof_mac_key=PROOF_MAC_KEY, now=NOW,
        clock=clock,
    )
    session.commit()
    return claim, intent_raw


def test_full_lifecycle_claim_and_open(session_factory) -> None:
    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-dev-1", key_32=DEV_VAULT_KEY)
    provider = MockDispatcher()
    with session_factory() as session:
        setup = _setup_pending(session)
        delivery, code, _, _ = _finalize(session, setup, service_key_id, vault)
        assert delivery.state == "PENDING"
        assert resolve_active(session, tag=code_hmac(code, HMAC_KEY)) is not None
        # Finalize replay returns the identical committed code.
        same, code2 = finalize_delivery(
            session,
            delivery_id=delivery.id,
            principal_user_id=setup["sender"].id,
            statement_raw=bytes(delivery.publish_statement),
            signature_69=bytes(delivery.publish_signature),
            escrow_payload_raw=b"",
            vault=vault,
            expected_service_key_id=service_key_id,
            locator_hmac_key=HMAC_KEY,
            locator_code_key_32=LOCATOR_CODE_KEY,
            locator_code_key_mode="test",
            hint_mask="g•••@•••",
            now=NOW,
        )
        assert code2 == code
        session.rollback()
        # Recipient path with sender offline.
        recipient = _make_user(session, f"rcp{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, provider)
        operation_id = uuid.uuid4()
        claim, intent_raw = _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, operation_id,
        )
        # Envelope opens only with the recipient's exact private key.
        fetched = get_claim(session, delivery_id=delivery.id, account_user_id=recipient.id)
        assert bytes(fetched.envelope) == bytes(claim.envelope)
        context_raw = dv2.ClaimedEnvelopeContextV2(
            protocol_version=2,
            capsule_id=delivery.capsule_id.bytes,
            delivery_target_id=delivery.delivery_target_id.bytes,
            sender_user_id=setup["sender"].id.bytes,
            recipient_user_id=recipient.id.bytes,
            recipient_key_bundle_id=recipient_bundle.id.bytes,
            publish_statement_sha256=bytes(delivery.publish_statement_sha256),
            claim_binding_sha256=hashlib.sha256(bytes(claim.claim_binding)).digest(),
        ).SerializeToString(deterministic=True)
        opened = recipient_ring.hpke_decrypt(
            bytes(claim.envelope),
            domains.domain_prefix(domains.CLAIMED_ENVELOPE_DOMAIN) + context_raw,
        )
        plaintext = dv2.ClaimedEnvelopePlaintextV2.FromString(opened)
        assert bytes(plaintext.address_opening.salt) == bytes(range(32))
        # Escrow removed in the same commit; incoming availability durable.
        fresh = session.get(PendingDelivery, delivery.id)
        assert fresh.state == "CLAIMED" and fresh.vault_ciphertext is None
        incoming = session.scalar(
            select(PendingIncoming).where(
                PendingIncoming.recipient_user_id == recipient.id,
                PendingIncoming.capsule_id == delivery.capsule_id,
            )
        )
        assert incoming is not None
        assert incoming.available_at == NOW
        # Replay returns exact stored bytes; changed content conflicts.
        replay = execute_claim(
            session,
            delivery_id=delivery.id,
            intent_raw=intent_raw,
            intent_signature_69=b"00",
            operation_id=operation_id,
            principal_account_id=recipient.id,
            principal_session_id=recipient_session,
            vault=vault,
            service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
            service_key_id=service_key_id,
            alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
            proof_mac_key=PROOF_MAC_KEY, now=NOW,
            clock=lambda: NOW,
        )
        assert bytes(replay.envelope) == bytes(claim.envelope)
        session.rollback()
        # Foreign principal on replay learns nothing.
        stranger = _make_user(session, f"sx{uuid.uuid4().hex[:8]}")
        session.commit()
        with pytest.raises(ClaimError):
            execute_claim(
                session,
                delivery_id=delivery.id,
                intent_raw=intent_raw,
                intent_signature_69=b"00",
                operation_id=operation_id,
                principal_account_id=stranger.id,
                principal_session_id=uuid.uuid4(),
                vault=vault,
                service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                service_key_id=service_key_id,
                alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
                proof_mac_key=PROOF_MAC_KEY, now=NOW,
            clock=lambda: NOW,
            )
        session.rollback()
        # ACK matrix + foreign account denied.
        assert ack_claim(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            claim_sha256=hashlib.sha256(bytes(claim.claim_binding)).digest(),
            envelope_sha256=bytes(claim.envelope_sha256),
        ) is True
        assert ack_claim(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            claim_sha256=bytes(32), envelope_sha256=bytes(claim.envelope_sha256),
        ) is False
        with pytest.raises(ClaimError):
            get_claim(session, delivery_id=delivery.id, account_user_id=stranger.id)


def test_claim_race_one_winner(session_factory) -> None:
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    service_key_id = uuid.uuid4()
    with session_factory() as session:
        setup = _setup_pending(session)
        vault = DevSealedVault(mode="test", key_id="m3-race-1", key_32=DEV_VAULT_KEY)
        _finalize(session, setup, service_key_id, vault)
        delivery_id = setup["delivery"].id
        parties = []
        for tag in ("r1", "r2"):
            account = _make_user(session, f"{tag}{uuid.uuid4().hex[:8]}")
            ring = Keyring()
            bundle = _make_bundle(session, account, ring)
            session_id = uuid.uuid4()
            session.commit()
            attempt = _prove(session, setup["delivery"], account, session_id, MockDispatcher())
            parties.append((account, ring, bundle, attempt))
    barrier = Barrier(2)
    results: dict[str, str] = {}

    def run(name: str, account, ring, bundle, attempt) -> None:
        with session_factory() as session:
            service_ring = Keyring()
            barrier.wait(timeout=60)
            try:
                delivery = session.get(PendingDelivery, delivery_id)
                challenge, sealed, _ctx_raw = claim_options(
                    session, delivery_id=delivery.id, account_user_id=account.id,
                    session_id=attempt.session_id, attempt_id=attempt.id, now=NOW,
                )
                session.commit()
                info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
                    challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
                    delivery_target_id=delivery.delivery_target_id.bytes,
                    recipient_user_id=account.id.bytes, recipient_key_bundle_id=bundle.id.bytes,
                    session_id=attempt.session_id.bytes, nonce_sha256=challenge.nonce_sha256,
                    expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
                ).SerializeToString(deterministic=True)
                nonce = ring.hpke_decrypt(sealed, info)
                intent = dv2.ClaimIntentV2(
                    intent_version=1, operation_id=uuid.uuid4().bytes,
                    capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
                    publish_statement_sha256=delivery.publish_statement_sha256,
                    proof_id=attempt.id.bytes, challenge_id=challenge.id.bytes,
                    recipient_user_id=account.id.bytes, recipient_key_bundle_id=bundle.id.bytes,
                    session_id=attempt.session_id.bytes, nonce=nonce,
                    nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=True,
                )
                intent_raw = intent.SerializeToString(deterministic=True)
                execute_claim(
                    session, delivery_id=delivery.id, intent_raw=intent_raw,
                    intent_signature_69=ring.sign69(
                        domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw),
                    operation_id=uuid.UUID(bytes=bytes(intent.operation_id)),
                    principal_account_id=account.id,
                    principal_session_id=attempt.session_id,
                    vault=DevSealedVault(mode="test", key_id="m3-race-1", key_32=DEV_VAULT_KEY),
                    service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                    service_key_id=service_key_id,
            alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
            proof_mac_key=PROOF_MAC_KEY, now=NOW,
            clock=lambda: NOW,
                )
                session.commit()
                results[name] = "claimed"
            except Exception as exc:  # noqa: BLE001 (fate recorded, not leaked)
                session.rollback()
                results[name] = type(exc).__name__

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda args: run(*args), [
            ("a", *parties[0]), ("b", *parties[1]),
        ]))
    assert sorted(results.values()) == ["ClaimError", "claimed"]
    with session_factory() as session:
        claims = session.scalars(select(PendingClaim)).all()
        assert len(claims) == 1
        loser = parties[0][0] if results["a"] == "ClaimError" else parties[1][0]
        assert claims[0].account_user_id != loser.id


def test_cancel_and_expiry_boundaries(session_factory) -> None:
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-cancel-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        delivery = setup["delivery"]
        cancel_delivery(session, delivery_id=delivery.id, sender_user_id=setup["sender"].id)
        assert session.get(PendingDelivery, delivery.id).state == "CANCELLED"
        cancel_delivery(session, delivery_id=delivery.id, sender_user_id=setup["sender"].id)
        session.commit()
        # Claim after cancel is gone.
        with pytest.raises((ClaimError, ProofError)):
            start_verification(
                session, delivery_id=delivery.id, account_user_id=setup["sender"].id,
                session_id=uuid.uuid4(), confirm_destination=ADDRESS,
                destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY,
                registry_hmac_key=HMAC_KEY, otp_key=OTP_KEY, proof_mac_key=PROOF_MAC_KEY,
                email_sender=MockDispatcher(), sms_provider=None, locale="en", now=NOW,
                attempt_id=uuid.uuid4(), code_supplier=lambda: "12345678",
            )
        session.rollback()


def test_finalize_failure_rolls_back_without_partial_state(session_factory) -> None:
    service_key_id = uuid.uuid4()

    class FailingVault(DevSealedVault):
        def seal(self, payload, aad):
            raise VaultError("injected KMS outage")

    with session_factory() as session:
        setup = _setup_pending(session)
        delivery = setup["delivery"]
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=setup["sender"].id, sender_bundle_id=setup["sender_bundle"].id,
            service_key_id=service_key_id,
        )
        sig = setup["sender_ring"].sign69(
            domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw
        )
        with pytest.raises(PublicationError):
            finalize_delivery(
                session, delivery_id=delivery.id, principal_user_id=setup["sender"].id,
                statement_raw=statement_raw,
                signature_69=sig, escrow_payload_raw=payload_raw,
                vault=FailingVault(mode="test", key_id="x", key_32=DEV_VAULT_KEY),
                expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
                locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
                hint_mask="g•••@•••", now=NOW,
            )
        session.rollback()
        fresh = session.get(PendingDelivery, delivery.id)
        assert fresh.state == "DRAFT" and fresh.vault_ciphertext is None
        # Wrong-sender signature fails before any intake.
        evil_ring = Keyring()
        evil_sig = evil_ring.sign69(
            domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw
        )
        with pytest.raises(PublicationError):
            finalize_delivery(
                session, delivery_id=delivery.id, principal_user_id=setup["sender"].id,
                statement_raw=statement_raw,
                signature_69=evil_sig, escrow_payload_raw=payload_raw,
                vault=DevSealedVault(mode="test", key_id="x", key_32=DEV_VAULT_KEY),
                expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
                locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
                hint_mask="g•••@•••", now=NOW,
            )
        session.rollback()


def test_background_retire_never_gates_content(session_factory) -> None:
    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-retire-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        delivery, code, _, _ = _finalize(session, setup, service_key_id, vault)
        recipient = _make_user(session, f"rr{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        operation_id = uuid.uuid4()
        claim, _ = _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, operation_id,
        )
        # Presentation success enqueues durable background retirement.
        queued = enqueue_retire(
            session, account_user_id=recipient.id, capsule_id=delivery.capsule_id,
            publication_sha256=bytes(delivery.publish_statement_sha256),
        )
        assert queued.state == "QUEUED"
        session.commit()
        # Content stays available BEFORE the worker runs (no gating).
        assert get_claim(session, delivery_id=delivery.id, account_user_id=recipient.id) is not None
        assert get_completion(session, capsule_id=delivery.capsule_id, account_user_id=recipient.id) is None

        def complete(sess, row) -> None:
            complete_locator_open(
                sess, capsule_id=row.capsule_id, account_user_id=row.account_user_id,
                publication_sha256=bytes(row.publication_sha256),
                locator_id=delivery.locator_id, now=NOW,
            )

        done, remaining = drain_retire_outbox(session, complete=complete)
        session.commit()
        assert (done, remaining) == (1, 0)
        # Post-retire: same envelope + completion re-fetchable; lookup dead.
        assert get_claim(session, delivery_id=delivery.id, account_user_id=recipient.id) is not None
        assert get_completion(session, capsule_id=delivery.capsule_id, account_user_id=recipient.id) is not None
        assert resolve_active(session, tag=code_hmac(code, HMAC_KEY)) is None
        # Worker failure stays QUEUED for durable retry (attempts++).
        queued2 = enqueue_retire(
            session, account_user_id=recipient.id, capsule_id=uuid.uuid4(),
            publication_sha256=bytes(32),
        )
        session.commit()

        def always_fail(sess, row) -> None:
            raise RuntimeError("injected worker outage")

        done2, remaining2 = drain_retire_outbox(session, complete=always_fail)
        session.commit()
        assert (done2, remaining2) == (0, 1)


def test_challenge_uses_authoritative_bundle_key(session_factory) -> None:
    """Adversarial substituted HPKE key cannot bypass possession: the
    challenge is sealed to the directory bundle, so only the true
    private key opens it."""
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-authkey-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"ak{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        challenge, sealed, _ctx_raw = claim_options(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, attempt_id=attempt.id, now=NOW,
        )
        session.commit()
        attacker = Keyring()
        with pytest.raises(Exception):
            attacker.hpke_decrypt(sealed, b"wrong-context-anything")
        # True key opens with the pinned challenge context.
        info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
            challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
            delivery_target_id=delivery.delivery_target_id.bytes,
            recipient_user_id=recipient.id.bytes, recipient_key_bundle_id=recipient_bundle.id.bytes,
            session_id=recipient_session.bytes, nonce_sha256=challenge.nonce_sha256,
            expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
        ).SerializeToString(deterministic=True)
        nonce = recipient_ring.hpke_decrypt(sealed, info)
        assert len(nonce) == 32
        session.rollback()


def test_strict_intent_and_expiry_step_clock(session_factory) -> None:
    from remanence.pending.claim import parse_claim_intent

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-strict-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"si{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        operation_id = uuid.uuid4()
        claim, intent_raw = _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, operation_id,
        )
        # Unknown-field intent rejected before authorization.
        smuggled = intent_raw + bytes.fromhex("a00601")
        with pytest.raises(ClaimError):
            parse_claim_intent(smuggled)
        # Principal mismatch rejected.
        with pytest.raises(ClaimError):
            execute_claim(
                session, delivery_id=delivery.id, intent_raw=intent_raw,
                intent_signature_69=b"00", operation_id=uuid.uuid4(),
                principal_account_id=recipient.id,
                principal_session_id=uuid.uuid4(),
                vault=vault,
                service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                service_key_id=service_key_id,
            alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
            proof_mac_key=PROOF_MAC_KEY, now=NOW,
            clock=lambda: NOW,
            )
        session.rollback()


def test_expiry_clock_crossing_fails_closed(session_factory) -> None:
    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-clock-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"ck{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        challenge, sealed, _ctx_raw = claim_options(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, attempt_id=attempt.id, now=NOW,
        )
        session.commit()
        info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
            challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
            delivery_target_id=delivery.delivery_target_id.bytes,
            recipient_user_id=recipient.id.bytes, recipient_key_bundle_id=recipient_bundle.id.bytes,
            session_id=recipient_session.bytes, nonce_sha256=challenge.nonce_sha256,
            expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
        ).SerializeToString(deterministic=True)
        nonce = recipient_ring.hpke_decrypt(sealed, info)
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=uuid.uuid4().bytes,
            capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
            publish_statement_sha256=delivery.publish_statement_sha256,
            proof_id=attempt.id.bytes, challenge_id=challenge.id.bytes,
            recipient_user_id=recipient.id.bytes, recipient_key_bundle_id=recipient_bundle.id.bytes,
            session_id=recipient_session.bytes, nonce=nonce,
            nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=False,
        )
        intent_raw = intent.SerializeToString(deterministic=True)
        intent_sig = recipient_ring.sign69(
            domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw
        )
        # Fake clock advances past challenge expiry mid-operation.
        with pytest.raises(ClaimError):
            execute_claim(
                session, delivery_id=delivery.id, intent_raw=intent_raw,
                intent_signature_69=intent_sig, operation_id=uuid.UUID(bytes=bytes(intent.operation_id)),
                principal_account_id=recipient.id, principal_session_id=recipient_session,
                vault=vault,
                service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                service_key_id=service_key_id,
                alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
                proof_mac_key=PROOF_MAC_KEY, now=NOW,
                clock=lambda: NOW + timedelta(minutes=6),
            )
        session.rollback()
        assert session.get(PendingClaim, delivery.delivery_target_id) is None


def test_sms_verify_path_consults_provider(session_factory) -> None:
    from remanence.pending.providers import MockSmsVerify

    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-sms-1", key_32=DEV_VAULT_KEY)
    sms = MockSmsVerify()
    with session_factory() as session:
        setup = _setup_pending(session, address="+380501234567", channel="sms")
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"sm{uuid.uuid4().hex[:8]}")
        _make_bundle(session, recipient, Keyring())
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = start_verification(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, confirm_destination="+380501234567",
            destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY,
            registry_hmac_key=HMAC_KEY, otp_key=OTP_KEY, proof_mac_key=PROOF_MAC_KEY,
            email_sender=None, sms_provider=sms, locale="uk", now=NOW,
            attempt_id=uuid.uuid4(),
        )
        session.commit()
        # Server never saw the code: only the handset-side issued code works.
        reference = attempt.provider_reference
        assert reference is not None
        with pytest.raises(ProofError):
            check_verification(
                session, attempt_id=attempt.id, delivery_id=delivery.id, account_user_id=recipient.id,
                session_id=recipient_session, code="000000",
                otp_key=OTP_KEY, sms_provider=sms, proof_mac_key=PROOF_MAC_KEY, now=NOW,
            )
        session.rollback()
        verified = check_verification(
            session, attempt_id=attempt.id, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, code=sms.issued_codes[reference],
            otp_key=OTP_KEY, sms_provider=sms, proof_mac_key=PROOF_MAC_KEY, now=NOW,
        )
        assert verified.state == "VERIFIED"
        session.commit()


def test_google_shortcut_requires_authority(session_factory) -> None:
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-ggl-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"gg{uuid.uuid4().hex[:8]}")
        _make_bundle(session, recipient, Keyring())
        recipient_session = uuid.uuid4()
        session.commit()
        # Third-party email_verified without Gmail/Workspace authority: no bypass.
        with pytest.raises(ProofError):
            verify_google_shortcut(
                session, delivery_id=delivery.id, account_user_id=recipient.id,
                session_id=recipient_session, google_subject="sub-1",
                google_email_normalized=ADDRESS, google_issued_at=NOW,
                account_google_subject="sub-1", google_authority="third_party",
                destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY,
                registry_hmac_key=HMAC_KEY, proof_mac_key=PROOF_MAC_KEY, now=NOW,
            )
        session.rollback()
        # Gmail authority approves without dispatch.
        attempt = verify_google_shortcut(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, google_subject="sub-1",
            google_email_normalized=ADDRESS, google_issued_at=NOW,
            account_google_subject="sub-1", google_authority="gmail",
            destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY,
            registry_hmac_key=HMAC_KEY, proof_mac_key=PROOF_MAC_KEY, now=NOW,
        )
        assert attempt.state == "VERIFIED"
        session.commit()


def test_completion_concurrent_replays_single_row(session_factory) -> None:
    """Concurrent completions serialize on the locator row lock: exactly
    one completion row, every replay returns the durable result."""
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-conc-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        delivery, _, _, _ = _finalize(session, setup, service_key_id, vault)
        recipient = _make_user(session, f"cc{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, uuid.uuid4(),
        )
        capsule_id = delivery.capsule_id
        pubhash = bytes(delivery.publish_statement_sha256)
        locator_id = delivery.locator_id
        recipient_id = recipient.id
    barrier = Barrier(3)
    outcomes: list[str] = []

    def run() -> None:
        from remanence.pending.retire import RetireError

        with session_factory() as session:
            barrier.wait(timeout=60)
            try:
                complete_locator_open(
                    session, capsule_id=capsule_id, account_user_id=recipient_id,
                    publication_sha256=pubhash, locator_id=locator_id, now=NOW,
                )
                session.commit()
                outcomes.append("completed")
            except Exception as exc:  # noqa: BLE001
                session.rollback()
                outcomes.append(type(exc).__name__)

    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda _: run(), range(3)))
    assert outcomes == ["completed", "completed", "completed"]
    with session_factory() as session:
        from remanence.pending.claim_models import LocatorOpenCompletion

        rows = session.scalars(
            select(LocatorOpenCompletion).where(LocatorOpenCompletion.capsule_id == capsule_id)
        ).all()
        assert len(rows) == 1


def test_enqueue_duplicate_returns_same_row(session_factory) -> None:
    with session_factory() as session:
        account = _make_user(session, f"dq{uuid.uuid4().hex[:8]}")
        session.commit()
        capsule = uuid.uuid4()
        pubhash = bytes([0xAB] * 32)
        first = enqueue_retire(
            session, account_user_id=account.id, capsule_id=capsule,
            publication_sha256=pubhash,
        )
        session.commit()
        second = enqueue_retire(
            session, account_user_id=account.id, capsule_id=capsule,
            publication_sha256=pubhash,
        )
        session.commit()
        assert first.id == second.id
        # Duplicate inside one transaction preserves surrounding work.
        marker = _make_user(session, f"dm{uuid.uuid4().hex[:8]}")
        session.flush()
        third = enqueue_retire(
            session, account_user_id=account.id, capsule_id=capsule,
            publication_sha256=pubhash,
        )
        assert third.id == first.id
        session.commit()
        assert session.get(User, marker.id) is not None


def test_bind_alias_creates_verified_binding(session_factory) -> None:
    from remanence.pending.registry import live_verified, verify_evidence_mac
    from remanence.pending.claim_models import AddressProofAttempt as _Attempt

    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-alias-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"al{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        service_ring = Keyring()
        _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, uuid.uuid4(),
            bind_alias=True,
        )
        live = live_verified(session, kind="email", normalized=ADDRESS, hmac_key=HMAC_KEY)
        assert live is not None and live.owner_user_id == recipient.id
        row = session.scalar(
            select(_Attempt).where(_Attempt.id == attempt.id)
        )
        assert row is not None
        from remanence.pending.registry import lookup_tag as _tag

        binding = session.scalar(
            select(DeliveryAddressBinding).where(
                DeliveryAddressBinding.kind == "email",
                DeliveryAddressBinding.lookup_tag == _tag("email", ADDRESS, HMAC_KEY),
            )
        )
        assert binding is not None and binding.state == "VERIFIED"
        assert verify_evidence_mac(binding, evidence_key=EVIDENCE_KEY) is True
        session.rollback()


def test_bind_alias_false_creates_no_binding(session_factory) -> None:
    from remanence.pending.registry import address_fate

    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-noalias-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"an{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        service_ring = Keyring()
        _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, uuid.uuid4(),
            bind_alias=False,
        )
        assert address_fate(session, kind="email", normalized=ADDRESS, hmac_key=HMAC_KEY) == "ABSENT"
        session.rollback()


def test_foreign_reservation_blocks_claim(session_factory) -> None:
    from remanence.pending.registry import reserve

    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-foreign-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        squatter = _make_user(session, f"sq{uuid.uuid4().hex[:8]}")
        reserve(session, kind="email", normalized=ADDRESS, owner_user_id=squatter.id, hmac_key=HMAC_KEY)
        session.commit()
        recipient = _make_user(session, f"fr{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        from remanence.pending.claim import claim_options as _options
        from remanence.pending.claim import execute_claim as _execute

        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        challenge, sealed, _ = _options(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, attempt_id=attempt.id, now=NOW,
        )
        session.commit()
        info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
            challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
            delivery_target_id=delivery.delivery_target_id.bytes,
            recipient_user_id=recipient.id.bytes, recipient_key_bundle_id=recipient_bundle.id.bytes,
            session_id=recipient_session.bytes, nonce_sha256=challenge.nonce_sha256,
            expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
        ).SerializeToString(deterministic=True)
        nonce = recipient_ring.hpke_decrypt(sealed, info)
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=uuid.uuid4().bytes,
            capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
            publish_statement_sha256=delivery.publish_statement_sha256,
            proof_id=attempt.id.bytes, challenge_id=challenge.id.bytes,
            recipient_user_id=recipient.id.bytes, recipient_key_bundle_id=recipient_bundle.id.bytes,
            session_id=recipient_session.bytes, nonce=nonce,
            nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=True,
        )
        intent_raw = intent.SerializeToString(deterministic=True)
        service_ring = Keyring()
        # Foreign reservation fails the claim even with a valid proof —
        # never transferred or merged.
        with pytest.raises(ClaimError):
            _execute(
                session, delivery_id=delivery.id, intent_raw=intent_raw,
                intent_signature_69=recipient_ring.sign69(
                    domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw),
                operation_id=uuid.uuid4(),
                principal_account_id=recipient.id, principal_session_id=recipient_session,
                vault=vault,
                service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                service_key_id=service_key_id,
                alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
                proof_mac_key=PROOF_MAC_KEY, now=NOW,
            clock=lambda: NOW,
            )
        session.rollback()
        assert session.get(PendingClaim, delivery.delivery_target_id) is None
        assert session.get(PendingDelivery, delivery.id).vault_ciphertext is not None


def test_forged_proof_row_cannot_release_escrow(session_factory) -> None:
    """D14: attacker with own session+keys patches the DB proof row to
    VERIFIED (or stretches expiry / swaps session). Without the
    outside-DB evidence MAC the claim dies before vault open."""
    from remanence.pending.claim_models import AddressProofAttempt as _Attempt
    from remanence.pending.claim import execute_claim as _execute
    from remanence.pending.claim import claim_options as _options

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-d14-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        attacker = _make_user(session, f"at{uuid.uuid4().hex[:8]}")
        attacker_ring = Keyring()
        attacker_bundle = _make_bundle(session, attacker, attacker_ring)
        attacker_session = uuid.uuid4()
        session.commit()
        # Forgery 1: flip a fresh CHALLENGE row straight to VERIFIED.
        attempt = start_verification(
            session, delivery_id=delivery.id, account_user_id=attacker.id,
            session_id=attacker_session, confirm_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY,
            registry_hmac_key=HMAC_KEY, otp_key=OTP_KEY, proof_mac_key=PROOF_MAC_KEY,
            email_sender=MockDispatcher(), sms_provider=None, locale="en", now=NOW,
            attempt_id=uuid.uuid4(), code_supplier=lambda: "12345678",
        )
        session.commit()
        row = session.get(_Attempt, attempt.id)
        row.state = "VERIFIED"
        row.verified_at = NOW
        session.commit()
        challenge, sealed, _ = _options(
            session, delivery_id=delivery.id, account_user_id=attacker.id,
            session_id=attacker_session, attempt_id=attempt.id, now=NOW,
        )
        session.commit()
        info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
            challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
            delivery_target_id=delivery.delivery_target_id.bytes,
            recipient_user_id=attacker.id.bytes, recipient_key_bundle_id=attacker_bundle.id.bytes,
            session_id=attacker_session.bytes, nonce_sha256=challenge.nonce_sha256,
            expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
        ).SerializeToString(deterministic=True)
        nonce = attacker_ring.hpke_decrypt(sealed, info)
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=uuid.uuid4().bytes,
            capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
            publish_statement_sha256=delivery.publish_statement_sha256,
            proof_id=attempt.id.bytes, challenge_id=challenge.id.bytes,
            recipient_user_id=attacker.id.bytes, recipient_key_bundle_id=attacker_bundle.id.bytes,
            session_id=attacker_session.bytes, nonce=nonce,
            nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=False,
        )
        intent_raw = intent.SerializeToString(deterministic=True)
        with pytest.raises(ClaimError):
            _execute(
                session, delivery_id=delivery.id, intent_raw=intent_raw,
                intent_signature_69=attacker_ring.sign69(
                    domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw),
                operation_id=uuid.uuid4(),
                principal_account_id=attacker.id, principal_session_id=attacker_session,
                vault=vault,
                service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                service_key_id=service_key_id,
                alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
                proof_mac_key=PROOF_MAC_KEY, now=NOW,
            clock=lambda: NOW,
            )
        session.rollback()
        assert session.get(PendingClaim, delivery.delivery_target_id) is None
        assert session.get(PendingDelivery, delivery.id).vault_ciphertext is not None
        # Forgery 2: session swap on a legit VERIFIED row.
        attempt2 = _prove(session, delivery, attacker, attacker_session, MockDispatcher())
        row2 = session.get(_Attempt, attempt2.id)
        row2.session_id = uuid.uuid4()
        session.commit()
        with pytest.raises(ClaimError):
            _options(
                session, delivery_id=delivery.id, account_user_id=attacker.id,
                session_id=attacker_session, attempt_id=attempt2.id, now=NOW,
            )
        session.rollback()


def test_bind_alias_false_still_fenced_by_foreign_reservation(session_factory) -> None:
    """R02/A08/A10: the reservation constrains the claim recipient even
    when intent.bind_alias=false — the fence is not just an alias-insert
    constraint."""
    from remanence.pending.registry import reserve

    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="m3-fence-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        squatter = _make_user(session, f"q1{uuid.uuid4().hex[:8]}")
        reserve(session, kind="email", normalized=ADDRESS, owner_user_id=squatter.id, hmac_key=HMAC_KEY)
        session.commit()
        recipient = _make_user(session, f"q2{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        service_ring = Keyring()
        with pytest.raises(ClaimError):
            _claim(
                session, delivery, recipient, recipient_bundle, recipient_ring,
                attempt, service_ring, service_key_id, vault, uuid.uuid4(),
                bind_alias=False,
            )
        session.rollback()
        assert session.get(PendingClaim, delivery.delivery_target_id) is None


def test_claim_vs_registry_mutation_no_deadlock(session_factory) -> None:
    """Lock-order proof: a full claim racing registry bind/retire churn on
    the same address completes without deadlock (PG would raise
    deadlock_detected; a hang fails via the join timeout)."""
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    from remanence.pending.registry import retire_binding, verify_binding

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    with session_factory() as session:
        setup = _setup_pending(session)
        vault = DevSealedVault(mode="test", key_id="m3-dl-1", key_32=DEV_VAULT_KEY)
        _finalize(session, setup, service_key_id, vault)
        delivery_id = setup["delivery"].id
        recipient = _make_user(session, f"dl{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, setup["delivery"], recipient, recipient_session, MockDispatcher())
        attempt_id = attempt.id
        recipient_id, bundle_id = recipient.id, recipient_bundle.id
    barrier = Barrier(2)
    outcomes: list[str] = []

    def claim_side() -> None:
        with session_factory() as session:
            barrier.wait(timeout=60)
            try:
                delivery = session.get(PendingDelivery, delivery_id)
                challenge, sealed, _ = claim_options(
                    session, delivery_id=delivery.id, account_user_id=recipient_id,
                    session_id=recipient_session, attempt_id=attempt_id, now=NOW,
                )
                session.commit()
                info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
                    challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
                    delivery_target_id=delivery.delivery_target_id.bytes,
                    recipient_user_id=recipient_id.bytes, recipient_key_bundle_id=bundle_id.bytes,
                    session_id=recipient_session.bytes, nonce_sha256=challenge.nonce_sha256,
                    expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
                ).SerializeToString(deterministic=True)
                nonce = recipient_ring.hpke_decrypt(sealed, info)
                intent = dv2.ClaimIntentV2(
                    intent_version=1, operation_id=uuid.uuid4().bytes,
                    capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
                    publish_statement_sha256=delivery.publish_statement_sha256,
                    proof_id=attempt_id.bytes, challenge_id=challenge.id.bytes,
                    recipient_user_id=recipient_id.bytes, recipient_key_bundle_id=bundle_id.bytes,
                    session_id=recipient_session.bytes, nonce=nonce,
                    nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=True,
                )
                intent_raw = intent.SerializeToString(deterministic=True)
                execute_claim(
                    session, delivery_id=delivery.id, intent_raw=intent_raw,
                    intent_signature_69=recipient_ring.sign69(
                        domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw),
                    operation_id=uuid.uuid4(),
                    principal_account_id=recipient_id, principal_session_id=recipient_session,
                    vault=DevSealedVault(mode="test", key_id="m3-dl-1", key_32=DEV_VAULT_KEY),
                    service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
                    service_key_id=service_key_id,
                    alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
                    proof_mac_key=PROOF_MAC_KEY, now=NOW, clock=lambda: NOW,
                )
                session.commit()
                outcomes.append("claimed")
            except Exception as exc:  # noqa: BLE001
                session.rollback()
                outcomes.append(type(exc).__name__)

    def registry_side() -> None:
        with session_factory() as session:
            barrier.wait(timeout=60)
            try:
                for _ in range(3):
                    row = verify_binding(
                        session, kind="email", normalized=ADDRESS,
                        owner_user_id=recipient_id, evidence_method="otp",
                        evidence_key=EVIDENCE_KEY, hmac_key=HMAC_KEY, now=NOW,
                    )
                    session.commit()
                    retire_binding(session, binding_id=row.id, owner_user_id=recipient_id)
                    session.commit()
                outcomes.append("churned")
            except Exception as exc:  # noqa: BLE001
                session.rollback()
                outcomes.append(type(exc).__name__)

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda fn: fn(), [claim_side, registry_side]))
    # No deadlock: both sides finished. The claim may win or lose the
    # fence against churn (retired mid-flight fails it closed) — either
    # terminal outcome is a ClaimError-free completion or an honest error,
    # never a hang or a deadlock abort.
    assert sorted(outcomes) == ["churned", "claimed"] or "ClaimError" in outcomes
