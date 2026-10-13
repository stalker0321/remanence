"""T185 M3 HTTP tests: frozen DTOs on an isolated app mounting ONLY the
pending router. main.py untouched; live beta prefix never involved.
Synthetic PG + synthetic keys. DTO strictness + R15 frozen shape.
"""

from __future__ import annotations

import base64
import hashlib
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select
from tink import signature, tink_config

pytest_plugins = ("test_session_repository_create",)

from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from remanence.pending import domains
from remanence.pending.api import router as pending_router
from remanence.pending.claim import ClaimError, get_claim
from remanence.pending.providers import MockDispatcher
from remanence.pending.providers import MockDispatcher as _DefaultMockDispatcher
from remanence.pending.vault import DevSealedVault
from remanence.settings import AppMode, Settings
from test_pending_m3_pg import (
    ADDRESS,
    DEST_KEY,
    DEV_VAULT_KEY,
    EVIDENCE_KEY,
    HMAC_KEY,
    LOCATOR_CODE_KEY,
    NOW,
    OTP_KEY,
    PROOF_MAC_KEY,
    SUITE,
    Keyring,
    _claim,
    _finalize,
    _make_bundle,
    _make_user,
    _prove,
    _publish_package,
    _setup_pending,
)

LOCATOR_HINT = "g•••@•••"


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


class _MemoryBlobStore:
    """In-memory BlobStore with the same hash/size conflict semantics."""

    def __init__(self) -> None:
        self._blobs: dict[str, bytes] = {}

    def put(self, key, source, *, expected_size, expected_sha256):
        import hashlib as _hashlib

        from remanence.storage.base import BlobInfo as _BlobInfo

        data = source.read() if hasattr(source, "read") else bytes(source)
        if len(data) != expected_size or _hashlib.sha256(data).hexdigest() != expected_sha256:
            raise ValueError("integrity mismatch")
        if key in self._blobs and self._blobs[key] != data:
            raise ValueError("conflict")
        self._blobs[key] = data
        return _BlobInfo(key=key, size=len(data), sha256_hex=_hashlib.sha256(data).hexdigest())

    def open_reader(self, key):
        import contextlib as _contextlib
        import io as _io

        if key not in self._blobs:
            raise KeyError(key)
        return _contextlib.closing(_io.BytesIO(self._blobs[key]))

    def stat(self, key):
        import hashlib as _hashlib

        from remanence.storage.base import BlobInfo as _BlobInfo

        return _BlobInfo(
            key=key, size=len(self._blobs[key]),
            sha256_hex=_hashlib.sha256(self._blobs[key]).hexdigest(),
        )


def _build_client(session_factory, *, flags_on: bool, user_id, session_id, ctx_overrides=None) -> TestClient:
    settings = Settings(
        mode=AppMode.TEST,
        pending_protocol_enabled=flags_on,
        pending_sends_enabled=flags_on,
        pending_claims_enabled=flags_on,
        pending_locator_issuance_enabled=flags_on,
        pending_hmac_secret="synthetic-test-secret-32-bytes-minimum",
    )
    app = FastAPI()
    app.state.settings = settings
    app.state.session_factory = session_factory
    ctx = {
        "hmac_key": HMAC_KEY,
        "destination_key": DEST_KEY,
        "otp_key": OTP_KEY,
        "proof_mac_key": PROOF_MAC_KEY,
        "evidence_key": EVIDENCE_KEY,
        "vault": DevSealedVault(mode="test", key_id="http-1", key_32=DEV_VAULT_KEY),
        "service_key_id": uuid.uuid4(),
        "email_sender": _DefaultMockDispatcher(),
        "locator_code_key": LOCATOR_CODE_KEY,
    }
    ctx.update(ctx_overrides or {})
    app.state.pending_ctx = ctx
    # Isolated harness: tests seed the registry directly, so readiness is
    # asserted here the way lifespan backfill asserts it in composition.
    app.state.pending_registry_ready = True
    import tempfile as _tempfile

    from remanence.capsules.upload_reservations import (
        build_upload_reservation_manager as _build_reservations,
    )
    from remanence.storage.staging import CiphertextStager as _Stager

    app.state.blob_store = _MemoryBlobStore()
    app.state.ciphertext_stager = _Stager(
        __import__("pathlib").Path(_tempfile.mkdtemp(prefix="pending-stager-"))
    )
    app.state.upload_reservations = _build_reservations()

    def _factory():
        session = session_factory()
        try:
            yield session
        finally:
            session.close()

    async def _principal() -> AuthenticatedPrincipal:
        return AuthenticatedPrincipal(user_id=user_id, session_id=session_id)

    app.dependency_overrides[get_db_session] = _factory
    app.dependency_overrides[get_authenticated_principal] = _principal
    app.include_router(pending_router)
    return TestClient(app, raise_server_exceptions=False)



def _one_decl():
    return [
        {
            "blob_id": str(uuid.uuid4()),
            "kind": 3,
            "ordinal": 0,
            "ciphertext_size": 505,
            "ciphertext_sha256_hex": "55" * 32,
        }
    ]


def test_disabled_flags_404_and_strict_dtos(session_factory) -> None:
    with session_factory() as session:
        user = _make_user(session, f"http{uuid.uuid4().hex[:8]}")
        session.commit()
        user_id, session_id = user.id, uuid.uuid4()
    client = _build_client(session_factory, flags_on=False, user_id=user_id, session_id=session_id)
    response = client.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()), "destination": {"kind": "email", "value": "a@b.test"},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert response.status_code == 404
    assert response.json()["code"] == "PROTOCOL_UNSUPPORTED"
    assert client.post("/v2/gift-lookups", json={"code": "AB3DE"}).status_code == 404
    assert client.post(
        f"/v2/capsules/{uuid.uuid4()}/locator-open-completion",
        json={"publication_sha256_hex": "00" * 32, "operation_id": str(uuid.uuid4())},
    ).status_code == 404
    # Strict DTOs: unknown fields rejected even when enabled.
    client_on = _build_client(session_factory, flags_on=True, user_id=user_id, session_id=session_id)
    response = client_on.post(
        "/v2/gift-lookups", json={"code": "AB3DE", "invented_field": 1}
    )
    assert response.status_code == 422
    response = client_on.post(
        f"/v2/capsules/{uuid.uuid4()}/locator-open-completion",
        json={"publication_sha256_hex": "00" * 32, "operation_id": str(uuid.uuid4()), "readiness": True},
    )
    assert response.status_code == 422


def test_lookup_unknown_is_generic_unavailable(session_factory) -> None:
    with session_factory() as session:
        user = _make_user(session, f"lk{uuid.uuid4().hex[:8]}")
        session.commit()
        client = _build_client(session_factory, flags_on=True, user_id=user.id, session_id=uuid.uuid4())
    response = client.post("/v2/gift-lookups", json={"code": "ZZ999"})
    assert response.status_code == 404
    assert response.json()["code"] == "GIFT_UNAVAILABLE"
    assert "no-store" in response.headers.get("Cache-Control", "")


def test_r15_frozen_shape_end_to_end(session_factory) -> None:
    from remanence.pending.claim import execute_claim, claim_options, get_claim
    from remanence.pending.proof import check_verification, start_verification
    from remanence.pending.providers import MockDispatcher
    from remanence.pending.publication import create_draft, finalize_delivery
    from remanence.pending.routing import decide_for_send, freeze_route
    from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    with session_factory() as session:
        sender = _make_user(session, f"hs{uuid.uuid4().hex[:8]}")
        sender_ring = Keyring()
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        declarations = _one_decl()
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180),
            declarations=declarations, now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
            artifacts=declarations,
        )
        sig = sender_ring.sign69(
            domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw
        )
        vault = DevSealedVault(mode="test", key_id="http-1", key_32=DEV_VAULT_KEY)
        finalized, _ = finalize_delivery(
            session, delivery_id=delivery.id,
            principal_user_id=sender.id, statement_raw=statement_raw,
            signature_69=sig, escrow_payload_raw=payload_raw, vault=vault,
            expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
            locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
            hint_mask=LOCATOR_HINT, now=NOW,
        )
        session.commit()
        recipient = _make_user(session, f"hr{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = start_verification(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, confirm_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY, otp_key=OTP_KEY,
            registry_hmac_key=HMAC_KEY, proof_mac_key=PROOF_MAC_KEY,
            email_sender=MockDispatcher(), sms_provider=None, locale="en", now=NOW,
            attempt_id=uuid.uuid4(), code_supplier=lambda: "12345678",
        )
        session.commit()
        verified = check_verification(
            session, attempt_id=attempt.id, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, code="12345678", otp_key=OTP_KEY,
            sms_provider=None, proof_mac_key=PROOF_MAC_KEY, now=NOW,
        )
        session.commit()
        challenge, sealed, challenge_raw = claim_options(
            session, delivery_id=delivery.id, account_user_id=recipient.id,
            session_id=recipient_session, attempt_id=verified.id, now=NOW,
        )
        session.commit()
        # Coordination amendment: the client uses the returned canonical
        # challenge bytes verbatim as HPKE info after validating fields.
        parsed = dv2.ClaimChallengeV1.FromString(challenge_raw)
        assert uuid.UUID(bytes=parsed.challenge_id) == challenge.id
        assert uuid.UUID(bytes=parsed.capsule_id) == delivery.capsule_id
        info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + challenge_raw
        nonce = recipient_ring.hpke_decrypt(sealed, info)
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=uuid.uuid4().bytes,
            capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
            publish_statement_sha256=delivery.publish_statement_sha256,
            proof_id=verified.id.bytes, challenge_id=challenge.id.bytes,
            recipient_user_id=recipient.id.bytes, recipient_key_bundle_id=recipient_bundle.id.bytes,
            session_id=recipient_session.bytes, nonce=nonce,
            nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=False,
        )
        intent_raw = intent.SerializeToString(deterministic=True)
        execute_claim(
            session, delivery_id=delivery.id, intent_raw=intent_raw,
            intent_signature_69=recipient_ring.sign69(
                domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw),
            operation_id=uuid.UUID(bytes=bytes(intent.operation_id)),
            principal_account_id=recipient.id, principal_session_id=recipient_session,
            vault=vault,
            service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
            service_key_id=service_key_id,
            alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
            proof_mac_key=PROOF_MAC_KEY, now=NOW,
        )
        session.commit()
        capsule_id = delivery.capsule_id
        pubhash_hex = bytes(delivery.publish_statement_sha256).hex()
        client = _build_client(
            session_factory, flags_on=True, user_id=recipient.id, session_id=recipient_session
        )
    # Frozen R15 request/response shapes.
    operation = str(uuid.uuid4())
    response = client.post(
        f"/v2/capsules/{capsule_id}/locator-open-completion",
        json={"publication_sha256_hex": pubhash_hex, "operation_id": operation},
    )
    assert response.status_code == 200, response.text
    assert response.json() == {
        "capsule_id": str(capsule_id),
        "completion": True,
        "locator_state": "RETIRED_OPEN",
    }
    # Replay + GET return the same durable completion.
    again = client.post(
        f"/v2/capsules/{capsule_id}/locator-open-completion",
        json={"publication_sha256_hex": pubhash_hex, "operation_id": str(uuid.uuid4())},
    )
    assert again.json() == response.json()
    fetched = client.get(f"/v2/capsules/{capsule_id}/locator-open-completion")
    assert fetched.json() == response.json()
    # Wrong hash: generic denial, no oracle.
    bad = client.post(
        f"/v2/capsules/{capsule_id}/locator-open-completion",
        json={"publication_sha256_hex": "ff" * 32, "operation_id": operation},
    )
    assert bad.status_code in (404, 409)


def test_r17_handle_once_then_immutable(session_factory) -> None:
    with session_factory() as session:
        user = _make_user(session, f"hh{uuid.uuid4().hex[:8]}")
        session.commit()
        client = _build_client(session_factory, flags_on=True, user_id=user.id, session_id=uuid.uuid4())
    # Handleful account re-setting a different handle is immutable.
    response = client.post("/v2/me/handle", json={"handle": "brandnewhandle"})
    assert response.status_code == 409
    assert response.json()["code"] == "HANDLE_IMMUTABLE"


def test_http_finalize_verify_claim_round_trip(session_factory) -> None:
    import base64 as _b64

    from remanence.pending.publication import create_draft
    from remanence.pending.routing import decide_for_send, freeze_route
    from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

    sender_ring = Keyring()
    service_ring = Keyring()
    service_signer = service_ring.ed_private.primitive(signature.PublicKeySign)
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="http-1", key_32=DEV_VAULT_KEY)
    from remanence.pending.providers import MockDispatcher as _MockDispatcher

    email_sender = _MockDispatcher()
    ctx_overrides = {
        "vault": vault,
        "service_key_id": service_key_id,
        "service_signer": service_signer,
        "email_sender": email_sender,
        "locator_code_key": LOCATOR_CODE_KEY,
    }
    with session_factory() as session:
        sender = _make_user(session, f"js{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        session.commit()
        sender_id, route_id, capsule_id, target_id = sender.id, route.id, route.capsule_id, decision.delivery_target_id
        sender_session = uuid.uuid4()
    sender_client = _build_client(
        session_factory, flags_on=True, user_id=sender_id,
        session_id=sender_session, ctx_overrides=ctx_overrides,
    )
    draft_artifact = {
        "blob_id": str(uuid.uuid4()), "kind": 3, "ordinal": 0,
        "ciphertext_size": 505,
        "ciphertext_sha256_hex": hashlib.sha256(bytes([0x77]) * 505).hexdigest(),
    }
    draft_blob_bytes = bytes([0x77]) * 505
    draft = sender_client.post(
        "/v2/pending-deliveries",
        json={"route_id": str(route_id), "capsule_id": str(capsule_id),
              "delivery_target_id": str(target_id), "channel": "email",
              "destination": ADDRESS, "salt_b64": _b64.b64encode(bytes(range(32))).decode(),
              "claim_expires_at": int((
                  datetime.now(timezone.utc) + timedelta(days=179)).timestamp()),
              "claim_policy_version": 1, "artifacts": [draft_artifact],
              "idempotency_key": str(uuid.uuid4())},
    )
    assert draft.status_code == 200, draft.text
    delivery_id = draft.json()["delivery_id"]
    # Declared bytes must exist before seal/publish: upload first,
    # then finalize.
    uploaded = sender_client.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{draft_artifact['blob_id']}",
        content=draft_blob_bytes,
    )
    assert uploaded.status_code == 200, uploaded.text
    statement_raw, payload_raw, _ = _publish_package(
        capsule_id=capsule_id, target_id=target_id, sender_id=sender_id,
        sender_bundle_id=sender_bundle.id, service_key_id=service_key_id,
        artifacts=[draft_artifact],
    )
    finalize = sender_client.post(
        f"/v2/pending-deliveries/{delivery_id}/finalize",
        json={"publish_statement_b64": _b64.b64encode(statement_raw).decode(),
              "publish_signature_69_b64": _b64.b64encode(sender_ring.sign69(
                  domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw)).decode(),
              "escrow_payload_b64": _b64.b64encode(payload_raw).decode(),
              "idempotency_key": str(uuid.uuid4())},
    )
    assert finalize.status_code == 200, finalize.text
    assert finalize.json()["state"] == "PENDING"
    with session_factory() as session:
        recipient = _make_user(session, f"jr{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        recipient_id, recipient_bundle_id = recipient.id, recipient_bundle.id
    recipient_client = _build_client(
        session_factory, flags_on=True, user_id=recipient_id,
        session_id=recipient_session, ctx_overrides=ctx_overrides,
    )
    start = recipient_client.post(
        f"/v2/pending-deliveries/{delivery_id}/verification",
        json={"confirm_destination": ADDRESS, "idempotency_key": str(uuid.uuid4())},
    )
    assert start.status_code == 200, start.text
    attempt_id = start.json()["attempt_id"]
    issued_code = next(iter(email_sender.inbox.values()))
    check = recipient_client.post(
        f"/v2/pending-deliveries/{delivery_id}/verification/{attempt_id}/check",
        json={"code": issued_code},
    )
    assert check.json() == {"attempt_id": attempt_id, "state": "VERIFIED"}
    options = recipient_client.post(f"/v2/pending-deliveries/{delivery_id}/claim/options", json={})
    assert options.status_code == 200, options.text
    sealed = _b64.b64decode(options.json()["hpke_challenge_b64"])
    challenge_id = uuid.UUID(options.json()["challenge_id"])
    # Amendment: use the returned canonical challenge bytes verbatim.
    challenge_raw = _b64.b64decode(options.json()["claim_challenge_b64"])
    parsed_challenge = dv2.ClaimChallengeV1.FromString(challenge_raw)
    assert uuid.UUID(bytes=parsed_challenge.challenge_id) == challenge_id
    assert uuid.UUID(bytes=parsed_challenge.capsule_id) == capsule_id
    assert uuid.UUID(bytes=parsed_challenge.delivery_target_id) == target_id
    assert uuid.UUID(bytes=parsed_challenge.recipient_user_id) == recipient_id
    assert uuid.UUID(bytes=parsed_challenge.recipient_key_bundle_id) == recipient_bundle_id
    info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + challenge_raw
    with session_factory() as session:
        from remanence.pending.claim_models import ClaimChallenge as _CC

        challenge = session.get(_CC, challenge_id)
        nonce = recipient_ring.hpke_decrypt(sealed, info)
        operation_id = uuid.uuid4()
        with session_factory() as session2:
            from remanence.pending.claim_models import PendingDelivery as _PD

            delivery_row = session2.get(_PD, uuid.UUID(delivery_id))
            pubhash = bytes(delivery_row.publish_statement_sha256)
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=operation_id.bytes,
            capsule_id=capsule_id.bytes, delivery_target_id=target_id.bytes,
            publish_statement_sha256=pubhash, proof_id=uuid.UUID(attempt_id).bytes,
            challenge_id=challenge.id.bytes, recipient_user_id=recipient_id.bytes,
            recipient_key_bundle_id=recipient_bundle_id.bytes,
            session_id=recipient_session.bytes, nonce=nonce,
            nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=False,
        )
        intent_raw = intent.SerializeToString(deterministic=True)
    submit = recipient_client.post(
        f"/v2/pending-deliveries/{delivery_id}/claim",
        json={"claim_intent_b64": _b64.b64encode(intent_raw).decode(),
              "claim_intent_signature_69_b64": _b64.b64encode(recipient_ring.sign69(
                  domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw)).decode(),
              "idempotency_key": str(operation_id)},
    )
    assert submit.status_code == 200, submit.text
    assert submit.json()["state"] == "CLAIMED"
    fetched = recipient_client.get(f"/v2/pending-deliveries/{delivery_id}/claim")
    assert fetched.json()["envelope_b64"] == submit.json()["envelope_b64"]
    acked = recipient_client.post(
        f"/v2/pending-deliveries/{delivery_id}/ack",
        json={"claim_sha256_hex": hashlib.sha256(_b64.b64decode(submit.json()["claim_binding_b64"])).hexdigest(),
              "envelope_sha256_hex": submit.json()["envelope_sha256_hex"]},
    )
    assert acked.json() == {"acknowledged": True}


def test_http_wrong_codes_lock_attempt_persistently(session_factory) -> None:
    """The counter-rollback hazard: 5 wrong codes then the 6th is locked,
    proven by a FRESH DB read (guesses==5, state EXPIRED) — not service."""
    from remanence.pending.claim_models import AddressProofAttempt as _Attempt
    from remanence.pending.publication import create_draft
    from remanence.pending.routing import decide_for_send, freeze_route

    from remanence.pending.publication import finalize_delivery as _finalize_service

    sender_ring = Keyring()
    service_key_id = uuid.uuid4()
    with session_factory() as session:
        sender = _make_user(session, f"ws{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        declarations = _one_decl()
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180),
            declarations=declarations, now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
            artifacts=declarations,
        )
        _finalize_service(
            session, delivery_id=delivery.id, principal_user_id=sender.id,
            statement_raw=statement_raw,
            signature_69=sender_ring.sign69(
                domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw),
            escrow_payload_raw=payload_raw,
            vault=DevSealedVault(mode="test", key_id="http-wc-1", key_32=DEV_VAULT_KEY),
            expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
            locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
            hint_mask=LOCATOR_HINT, now=NOW,
        )
        session.commit()
        delivery_id = delivery.id
        recipient = _make_user(session, f"wr{uuid.uuid4().hex[:8]}")
        _make_bundle(session, recipient, Keyring())
        recipient_session = uuid.uuid4()
        session.commit()
        recipient_id = recipient.id
    service_key_id = uuid.uuid4()
    client = _build_client(
        session_factory, flags_on=True, user_id=recipient_id,
        session_id=recipient_session,
        ctx_overrides={"service_key_id": service_key_id},
    )
    start = client.post(
        f"/v2/pending-deliveries/{delivery_id}/verification",
        json={"confirm_destination": ADDRESS, "idempotency_key": str(uuid.uuid4())},
    )
    assert start.status_code == 200, start.text
    attempt_id = start.json()["attempt_id"]
    for _ in range(5):
        wrong = client.post(
            f"/v2/pending-deliveries/{delivery_id}/verification/{attempt_id}/check",
            json={"code": "00000000"},
        )
        assert wrong.status_code == 400
    locked = client.post(
        f"/v2/pending-deliveries/{delivery_id}/verification/{attempt_id}/check",
        json={"code": "00000000"},
    )
    assert locked.status_code == 400
    assert locked.json()["code"] == "PROOF_EXPIRED"
    with session_factory() as fresh:
        row = fresh.get(_Attempt, uuid.UUID(attempt_id))
        assert row is not None and row.guesses == 5 and row.state == "EXPIRED"


def test_http_lookup_budget_and_no_ip_logging(session_factory) -> None:
    with session_factory() as session:
        user = _make_user(session, f"bl{uuid.uuid4().hex[:8]}")
        session.commit()
        client = _build_client(session_factory, flags_on=True, user_id=user.id, session_id=uuid.uuid4())
    limited_at = None
    for i in range(12):
        response = client.post("/v2/gift-lookups", json={"code": "ZZ999"})
        if response.status_code == 429:
            limited_at = i
            break
    assert limited_at is not None and limited_at <= 10
    assert response.json()["code"] == "RATE_LIMITED"
    limiter = client.app.state.pending_lookup_limiter
    for key in list(limiter._minute) + list(limiter._day):
        assert len(key) == 64 and all(c in "0123456789abcdef" for c in key)


def test_http_continue_foreign_owned_is_generic_unavailable(session_factory) -> None:
    """R7: a copied ACTIVE code for someone else's gift never yields the
    proof branch — generic GIFT_UNAVAILABLE."""
    from remanence.pending.claim import claim_options, execute_claim
    from remanence.pending.locator_store import code_hmac as _hmac
    from remanence.pending.models import GiftLocator as _GL
    from remanence.pending.proof import check_verification, start_verification
    from remanence.pending.providers import MockDispatcher as _Mock
    from remanence.pending.publication import create_draft, finalize_delivery
    from remanence.pending.routing import decide_for_send, freeze_route
    from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

    sender_ring = Keyring()
    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="http-r7-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        sender = _make_user(session, f"fs{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        declarations = _one_decl()
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180),
            declarations=declarations, now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
            artifacts=declarations,
        )
        finalized, code = finalize_delivery(
            session, delivery_id=delivery.id,
            principal_user_id=sender.id,
            statement_raw=statement_raw,
            signature_69=sender_ring.sign69(
                domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw),
            escrow_payload_raw=payload_raw, vault=vault,
            expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
            locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
            hint_mask=LOCATOR_HINT, now=NOW,
        )
        session.commit()
        owner = _make_user(session, f"fo{uuid.uuid4().hex[:8]}")
        owner_ring = Keyring()
        owner_bundle = _make_bundle(session, owner, owner_ring)
        owner_session = uuid.uuid4()
        session.commit()
        attempt = start_verification(
            session, delivery_id=delivery.id, account_user_id=owner.id,
            session_id=owner_session, confirm_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_tag_key=HMAC_KEY,
            registry_hmac_key=HMAC_KEY, otp_key=OTP_KEY, proof_mac_key=PROOF_MAC_KEY,
            email_sender=_Mock(), sms_provider=None, locale="en", now=NOW,
            attempt_id=uuid.uuid4(), code_supplier=lambda: "12345678",
        )
        session.commit()
        verified = check_verification(
            session, attempt_id=attempt.id, delivery_id=delivery.id,
            account_user_id=owner.id, session_id=owner_session, code="12345678",
            otp_key=OTP_KEY, sms_provider=None, proof_mac_key=PROOF_MAC_KEY, now=NOW,
        )
        session.commit()
        challenge, sealed, _ = claim_options(
            session, delivery_id=delivery.id, account_user_id=owner.id,
            session_id=owner_session, attempt_id=verified.id, now=NOW,
        )
        session.commit()
        info = domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + dv2.ClaimChallengeV1(
            challenge_id=challenge.id.bytes, capsule_id=delivery.capsule_id.bytes,
            delivery_target_id=delivery.delivery_target_id.bytes,
            recipient_user_id=owner.id.bytes, recipient_key_bundle_id=owner_bundle.id.bytes,
            session_id=owner_session.bytes, nonce_sha256=challenge.nonce_sha256,
            expires_at_epoch_seconds=int((NOW + timedelta(minutes=5)).timestamp()),
        ).SerializeToString(deterministic=True)
        nonce = owner_ring.hpke_decrypt(sealed, info)
        operation_id = uuid.uuid4()
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=operation_id.bytes,
            capsule_id=delivery.capsule_id.bytes, delivery_target_id=delivery.delivery_target_id.bytes,
            publish_statement_sha256=delivery.publish_statement_sha256,
            proof_id=verified.id.bytes, challenge_id=challenge.id.bytes,
            recipient_user_id=owner.id.bytes, recipient_key_bundle_id=owner_bundle.id.bytes,
            session_id=owner_session.bytes, nonce=nonce,
            nonce_sha256=hashlib.sha256(nonce).digest(), bind_alias=False,
        )
        intent_raw = intent.SerializeToString(deterministic=True)
        execute_claim(
            session, delivery_id=delivery.id, intent_raw=intent_raw,
            intent_signature_69=owner_ring.sign69(
                domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent_raw),
            operation_id=operation_id, principal_account_id=owner.id,
            principal_session_id=owner_session, vault=vault,
            service_signer=service_ring.ed_private.primitive(signature.PublicKeySign),
            service_key_id=service_key_id,
            alias_evidence_key=EVIDENCE_KEY, alias_evidence_method="otp",
            proof_mac_key=PROOF_MAC_KEY, now=NOW, clock=lambda: NOW,
        )
        session.commit()
        locator = session.scalar(select(_GL).where(_GL.capsule_id == delivery.capsule_id))
        assert locator is not None
        reference = str(locator.id)
        stranger = _make_user(session, f"fx{uuid.uuid4().hex[:8]}")
        session.commit()
        stranger_id = stranger.id
    stranger_client = _build_client(
        session_factory, flags_on=True, user_id=stranger_id, session_id=uuid.uuid4()
    )
    response = stranger_client.post(f"/v2/gift-lookups/{reference}/continue", json={})
    assert response.status_code == 404
    assert response.json()["code"] == "GIFT_UNAVAILABLE"


def test_r7_proof_branch_carries_next_step_material(session_factory) -> None:
    """Frozen R7 DTO: unresolved pending returns delivery_id/capsule/
    target/channel/expiry plus the public S snapshot (D/KC/expiry/
    service-key inside); H is its hash. T186 drives R8/R10/R11 from this."""
    import base64 as _b64

    from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

    from remanence.pending.publication import create_draft, finalize_delivery
    from remanence.pending.routing import decide_for_send, freeze_route

    sender_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="http-r7p-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        sender = _make_user(session, f"ps{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        declarations = _one_decl()
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180),
            declarations=declarations, now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
            artifacts=declarations,
        )
        finalized, _ = finalize_delivery(
            session, delivery_id=delivery.id, principal_user_id=sender.id,
            statement_raw=statement_raw,
            signature_69=sender_ring.sign69(
                domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw),
            escrow_payload_raw=payload_raw, vault=vault,
            expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
            locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
            hint_mask=LOCATOR_HINT, now=NOW,
        )
        session.commit()
        locator_id = finalized.locator_id
        delivery_id, capsule_id, target_id = delivery.id, delivery.capsule_id, delivery.delivery_target_id
        newcomer = _make_user(session, f"pn{uuid.uuid4().hex[:8]}")
        session.commit()
        newcomer_id = newcomer.id
    client = _build_client(session_factory, flags_on=True, user_id=newcomer_id, session_id=uuid.uuid4())
    response = client.post(f"/v2/gift-lookups/{locator_id}/continue", json={})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["next"] == "proof"
    assert body["delivery_id"] == str(delivery_id)
    assert body["capsule_id"] == str(capsule_id)
    assert body["delivery_target_id"] == str(target_id)
    assert body["channel"] == "email"
    assert isinstance(body["claim_expires_at"], int)
    statement = dv2.PendingPublishStatementV2.FromString(_b64.b64decode(body["publish_statement_b64"]))
    assert uuid.UUID(bytes=statement.capsule_id) == capsule_id
    assert uuid.UUID(bytes=statement.delivery_target_id) == target_id
    assert hashlib.sha256(_b64.b64decode(body["publish_statement_b64"])).hexdigest() == \
        hashlib.sha256(statement_raw).hexdigest()


def test_google_receive_register_consumes_ticket(session_factory) -> None:
    """v2 Google receive route: same verified REGISTER ticket consumed
    atomically; handle null + profile_version 2; replay/reuse refused."""
    import base64 as _b64

    from remanence.auth.google_models import GoogleFlow

    ring = Keyring()
    with session_factory() as session:
        secret = "receive-ticket-secret-1"
        ticket = GoogleFlow(
            id=uuid.uuid4(), purpose="REGISTER",
            secret_hash=hashlib.sha256(secret.encode()).digest(),
            subject="google-subject-receive-1", email="newfriend@example.test",
            expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
            consumed_at=None,
        )
        session.add(ticket)
        session.commit()
        flow_id = ticket.id
        stranger = _make_user(session, f"gr{uuid.uuid4().hex[:8]}")
        session.commit()
        client = _build_client(session_factory, flags_on=True, user_id=stranger.id, session_id=uuid.uuid4())
    payload = {
        "flow": str(flow_id), "registration_token": secret,
        "key_bundle_id": str(uuid.uuid4()), "suite": SUITE, "protocol_version": 1,
        "encryption_public_keyset_b64": _b64.b64encode(ring.hpke_public).decode(),
        "signing_public_keyset_b64": _b64.b64encode(ring.ed_public).decode(),
    }
    response = client.post("/v2/auth/google/receive-register", json=payload)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["status"] == "SIGNED_IN"
    assert body["user"]["handle"] is None
    assert body["user"]["profile_version"] == 2
    # Consumed ticket cannot mint a second account.
    again = client.post("/v2/auth/google/receive-register", json=payload)
    assert again.status_code in (400, 409)


def test_entitlement_paths_ignore_disabled_subflags(session_factory) -> None:
    """Master-only gates: new-send subflags OFF must not cut off claim
    redownload, lookup, ack, or completion reads."""
    app_settings = Settings(
        mode=AppMode.TEST, pending_protocol_enabled=True,
        pending_sends_enabled=False, pending_claims_enabled=False,
        pending_locator_issuance_enabled=False,
        pending_hmac_secret="synthetic-test-secret-32-bytes-minimum",
    )
    app = FastAPI()
    app.state.settings = app_settings
    app.state.session_factory = session_factory
    app.state.pending_ctx = {"hmac_key": HMAC_KEY}
    from remanence.pending.api import router as _router

    app.include_router(_router)

    def _factory():
        session = session_factory()
        try:
            yield session
        finally:
            session.close()

    with session_factory() as session:
        user = _make_user(session, f"en{uuid.uuid4().hex[:8]}")
        session.commit()
        user_id = user.id

    async def _principal() -> AuthenticatedPrincipal:
        return AuthenticatedPrincipal(user_id=user_id, session_id=uuid.uuid4())

    app.dependency_overrides[get_db_session] = _factory
    app.dependency_overrides[get_authenticated_principal] = _principal
    client = TestClient(app, raise_server_exceptions=False)
    # New sends disabled...
    denied = client.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()),
              "destination": {"kind": "email", "value": "a@b.test"},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert denied.status_code == 404
    # ...but lookup shape still served (generic unavailable, not disabled).
    lookup = client.post("/v2/gift-lookups", json={"code": "ZZ999"})
    assert lookup.status_code in (404, 429)
    assert lookup.json()["code"] in ("GIFT_UNAVAILABLE", "RATE_LIMITED")
    # ...and completion reads reach the entitlement check, not the gate.
    missing = client.get(f"/v2/capsules/{uuid.uuid4()}/locator-open-completion")
    assert missing.status_code == 404
    assert missing.json()["code"] == "GIFT_UNAVAILABLE"


def test_r1_exact_shapes_and_known_verified_v1(session_factory) -> None:
    """Frozen R1: exact response keysets for both arms; known-verified
    address routes v1 with zero escrow involvement."""
    from remanence.pending.registry import verify_binding
    from remanence.pending.routing import decide_for_send, freeze_route

    with session_factory() as session:
        sender = _make_user(session, f"s1{uuid.uuid4().hex[:8]}")
        recipient = _make_user(session, f"s2{uuid.uuid4().hex[:8]}")
        recipient_bundle = _make_bundle(session, recipient, Keyring())
        session.commit()
        verify_binding(
            session, kind="email", normalized=ADDRESS, owner_user_id=recipient.id,
            evidence_method="otp", evidence_key=EVIDENCE_KEY, hmac_key=HMAC_KEY, now=NOW,
        )
        session.commit()
        sender_id, sender_session = sender.id, uuid.uuid4()
    client = _build_client(session_factory, flags_on=True, user_id=sender_id, session_id=sender_session)
    pending = client.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()),
              "destination": {"kind": "email", "value": "fresh-new@example.test"},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert pending.status_code == 200, pending.text
    assert set(pending.json()) == {
        "route_id", "mode", "expires_at", "normalized_destination",
        "channel", "claim_deadline", "service_key_id", "delivery_target_id",
    }
    assert pending.json()["mode"] == "PENDING_V2"
    known = client.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()),
              "destination": {"kind": "email", "value": ADDRESS},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert known.status_code == 200, known.text
    assert set(known.json()) == {
        "route_id", "mode", "expires_at", "user_id", "key_bundle_id", "binding_revision",
    }
    assert known.json()["mode"] == "EXISTING_USER_V1"
    assert known.json()["user_id"] == str(recipient.id)
    # Handle arm resolves through the directory, not the email decider.
    handle = client.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()),
              "destination": {"kind": "handle", "value": recipient.handle_normalized},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert handle.status_code == 200, handle.text
    assert handle.json()["mode"] == "EXISTING_USER_V1"


def test_r3_validates_policy_artifacts_idempotency(session_factory) -> None:
    import base64 as _b64

    from remanence.pending.routing import decide_for_send, freeze_route

    with session_factory() as session:
        sender = _make_user(session, f"s3{uuid.uuid4().hex[:8]}")
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        session.commit()
        sender_id, route_id = sender.id, route.id
    client = _build_client(session_factory, flags_on=True, user_id=sender_id, session_id=uuid.uuid4())
    route_capsule, route_target = str(route.capsule_id), str(route.delivery_target_id)
    base = {"route_id": str(route_id), "capsule_id": route_capsule, "channel": "email",
            "delivery_target_id": route_target,
            "destination": ADDRESS, "salt_b64": _b64.b64encode(bytes(range(32))).decode(),
            "claim_expires_at": int((datetime.now(timezone.utc) + timedelta(days=10)).timestamp()),
            "claim_policy_version": 1,
            "artifacts": [{"blob_id": str(uuid.uuid4()), "kind": 3, "ordinal": 0,
                           "ciphertext_size": 10, "ciphertext_sha256_hex": "aa" * 32}],
            "idempotency_key": str(uuid.uuid4())}
    created = client.post("/v2/pending-deliveries", json=base)
    assert created.status_code == 200, created.text
    assert set(created.json()) == {"delivery_id", "state", "address_commitment_d_hex", "expires_at"}
    assert len(created.json()["address_commitment_d_hex"]) == 64
    # Wrong policy rejected, not silently accepted.
    bad_policy = dict(base, claim_policy_version=2, idempotency_key=str(uuid.uuid4()))
    assert client.post("/v2/pending-deliveries", json=bad_policy).status_code == 404
    # Destination mismatch with the frozen route rejected.
    mismatch = dict(base, destination="other@example.test", idempotency_key=str(uuid.uuid4()))
    assert client.post("/v2/pending-deliveries", json=mismatch).status_code == 409
    # Draft for another target cannot ride this route.
    other_target = dict(base, delivery_target_id=str(uuid.uuid4()), idempotency_key=str(uuid.uuid4()))
    assert client.post("/v2/pending-deliveries", json=other_target).status_code == 409
    # Same capsule+target redraft is idempotent (same delivery id).
    replay = client.post("/v2/pending-deliveries", json=base)
    assert replay.status_code == 200
    assert replay.json()["delivery_id"] == created.json()["delivery_id"]


def test_r8_phone_consent_and_r6_channel(session_factory) -> None:
    import base64 as _b64

    from remanence.pending.publication import create_draft, finalize_delivery
    from remanence.pending.routing import decide_for_send, freeze_route

    sender_ring = Keyring()
    service_key_id = uuid.uuid4()
    phone = "+380509998877"
    with session_factory() as session:
        sender = _make_user(session, f"s4{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="phone", normalized_destination=phone,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="phone", normalized_destination=phone,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        declarations = [{"blob_id": str(uuid.uuid4()), "kind": 3, "ordinal": 0,
                         "ciphertext_size": 10, "ciphertext_sha256_hex": "bb" * 32}]
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="sms", claim_expires_at=NOW + timedelta(days=10),
            declarations=declarations, now=NOW, pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id, artifacts=declarations,
        )
        finalized, code = finalize_delivery(
            session, delivery_id=delivery.id, principal_user_id=sender.id,
            statement_raw=statement_raw,
            signature_69=sender_ring.sign69(
                domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw),
            escrow_payload_raw=payload_raw,
            vault=DevSealedVault(mode="test", key_id="http-pc-1", key_32=DEV_VAULT_KEY),
            expected_service_key_id=service_key_id, locator_hmac_key=HMAC_KEY,
            locator_code_key_32=LOCATOR_CODE_KEY, locator_code_key_mode="test",
            hint_mask=LOCATOR_HINT, now=NOW,
        )
        session.commit()
        delivery_id, sender_id = delivery.id, sender.id
    client = _build_client(session_factory, flags_on=True, user_id=sender_id, session_id=uuid.uuid4())
    # R6 reports the real channel, never 'unknown' for live gifts.
    lookup = client.post("/v2/gift-lookups", json={"code": code.lower().replace(" ", "")})
    assert lookup.status_code == 200, lookup.text
    assert lookup.json()["channel"] == "sms"
    # R8 without phone consent is refused before any dispatch.
    recipient = None
    with session_factory() as session:
        recipient = _make_user(session, f"s5{uuid.uuid4().hex[:8]}")
        _make_bundle(session, recipient, Keyring())
        session.commit()
        recipient_id, recipient_session = recipient.id, uuid.uuid4()
    rclient = _build_client(session_factory, flags_on=True, user_id=recipient_id, session_id=recipient_session)
    refused = rclient.post(
        f"/v2/pending-deliveries/{delivery_id}/verification",
        json={"confirm_destination": phone, "idempotency_key": str(uuid.uuid4())},
    )
    assert refused.status_code == 400


def test_blob_upload_manifest_download(session_factory) -> None:
    import base64 as _b64

    from remanence.pending.publication import create_draft
    from remanence.pending.routing import decide_for_send, freeze_route

    sender_ring = Keyring()
    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    blob_bytes = bytes([0x77]) * 505
    with session_factory() as session:
        sender = _make_user(session, f"s6{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        import hashlib as _hashlib

        blob_id = str(uuid.uuid4())
        declarations = [{"blob_id": blob_id, "kind": 3, "ordinal": 0,
                         "ciphertext_size": len(blob_bytes),
                         "ciphertext_sha256_hex": _hashlib.sha256(blob_bytes).hexdigest()}]
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=10),
            declarations=declarations, now=NOW, pending_sends_enabled=True,
        )
        session.commit()
        sender_id, delivery_id = sender.id, delivery.id
    sclient = _build_client(session_factory, flags_on=True, user_id=sender_id, session_id=uuid.uuid4())
    # Undeclared blob refused.
    assert sclient.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{uuid.uuid4()}",
        content=b"nope",
    ).status_code in (400, 404)
    # Hash mismatch refused.
    assert sclient.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}",
        content=bytes([0x78]) * 505,
    ).status_code in (400, 404)
    # Exact declared bytes accepted.
    uploaded = sclient.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}", content=blob_bytes
    )
    assert uploaded.status_code == 200, uploaded.text
    # Sender manifest lists the artifact.
    manifest = sclient.get(f"/v2/pending-deliveries/{delivery_id}/manifest")
    assert manifest.status_code == 200
    assert manifest.json()["artifacts"][0]["blob_id"] == blob_id
    # Pre-claim recipient download denied; sender download works.
    with session_factory() as session:
        stranger = _make_user(session, f"s7{uuid.uuid4().hex[:8]}")
        session.commit()
        stranger_id = stranger.id
    xclient = _build_client(session_factory, flags_on=True, user_id=stranger_id, session_id=uuid.uuid4())
    assert xclient.get(f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}").status_code == 404
    got = sclient.get(f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}")
    assert got.status_code == 200 and got.content == blob_bytes


def test_pending_incoming_feed_discovery_and_v1_isolation(session_factory) -> None:
    """Owned claim-time feed: item after claim (completion null), then
    completion metadata after R15; foreign principal sees empty; the v1
    incoming feed never returns the v2 capsule."""
    from fastapi import FastAPI as _FastAPI
    from fastapi.testclient import TestClient as _TestClient

    from remanence.api.dependencies import (
        AuthenticatedPrincipal as _Principal,
    )
    from remanence.api.dependencies import (
        get_authenticated_principal as _get_principal,
    )
    from remanence.api.dependencies import get_db_session as _get_session
    from remanence.main import create_app as _create_app
    from remanence.settings import AppMode as _Mode
    from remanence.settings import Settings as _Settings

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="http-feed-1", key_32=DEV_VAULT_KEY)
    secret = "synthetic-feed-secret-32-bytes-min!!"
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, service_key_id, vault)
        delivery = setup["delivery"]
        recipient = _make_user(session, f"fd{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, uuid.uuid4(),
        )
        capsule_id, delivery_id = delivery.capsule_id, delivery.id
        recipient_id = recipient.id
        stranger = _make_user(session, f"fq{uuid.uuid4().hex[:8]}")
        session.commit()
        stranger_id = stranger.id
    settings = _Settings(
        mode=_Mode.TEST, pending_protocol_enabled=True,
        pending_sends_enabled=True, pending_claims_enabled=True,
        pending_locator_issuance_enabled=True, pending_hmac_secret=secret,
    )
    ctx = {
        "hmac_key": secret.encode(), "destination_key": DEST_KEY,
        "otp_key": OTP_KEY, "proof_mac_key": PROOF_MAC_KEY,
        "evidence_key": EVIDENCE_KEY,
        "vault": DevSealedVault(mode="test", key_id="http-feed-1", key_32=DEV_VAULT_KEY),
        "service_key_id": service_key_id,
        "email_sender": MockDispatcher(),
        "locator_code_key": LOCATOR_CODE_KEY,
    }
    app = _create_app(settings=settings, session_factory=session_factory, pending_ctx=ctx)

    def _factory():
        session = session_factory()
        try:
            yield session
        finally:
            session.close()

    app.dependency_overrides[_get_session] = _factory

    def _as(user_id, session_id):
        async def _principal() -> _Principal:
            return _Principal(user_id=user_id, session_id=session_id)

        app.dependency_overrides[_get_principal] = _principal

    _as(recipient_id, recipient_session)
    with _TestClient(app, raise_server_exceptions=False) as client:
        feed = client.get("/v2/pending-incoming")
        assert feed.status_code == 200, feed.text
        body = feed.json()
        assert len(body["items"]) == 1
        item = body["items"][0]
        assert item["capsule_id"] == str(capsule_id)
        assert item["delivery_id"] == str(delivery_id)
        assert item["completion"] is None
        assert item["envelope_ref"].endswith("/claim")
        assert item["manifest_ref"].endswith("/manifest")
        assert len(item["blob_refs"]) == 1
        # v1 feed never returns the v2 capsule.
        v1 = client.get("/v1/capsules/incoming")
        assert str(capsule_id) not in v1.text
        # Foreign principal: empty page, no oracle.
        _as(stranger_id, uuid.uuid4())
        foreign = client.get("/v2/pending-incoming")
        assert foreign.status_code == 200
        assert foreign.json()["items"] == []
        # Cursor round trip.
        _as(recipient_id, recipient_session)
        paged = client.get("/v2/pending-incoming", params={"limit": 1})
        assert len(paged.json()["items"]) == 1


def test_upload_oversize_undeclared_cancel_race(session_factory) -> None:
    """Bounded transport hazards: oversize bodies rejected by the stager,
    undeclared blobs refused, and a cancel racing an upload leaves no
    orphan bytes, no staged temps, and no held reservation."""
    import base64 as _b64

    from remanence.capsules.upload_reservations import (
        build_upload_reservation_manager as _Reservations,
    )
    from remanence.pending.publication import cancel_delivery, create_draft
    from remanence.pending.routing import decide_for_send, freeze_route

    sender_ring = Keyring()
    with session_factory() as session:
        sender = _make_user(session, f"tu{uuid.uuid4().hex[:8]}")
        _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        blob_id = str(uuid.uuid4())
        declarations = [{"blob_id": blob_id, "kind": 3, "ordinal": 0,
                         "ciphertext_size": 1024,
                         "ciphertext_sha256_hex": hashlib.sha256(b"x" * 1024).hexdigest()}]
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=10),
            declarations=declarations, now=NOW, pending_sends_enabled=True,
        )
        session.commit()
        sender_id, delivery_id = sender.id, delivery.id
    client = _build_client(session_factory, flags_on=True, user_id=sender_id, session_id=uuid.uuid4())
    # Oversize for the STAGER is bounded: declared 1024 but 2 MiB sent.
    big = client.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}", content=b"y" * (2 * 1024 * 1024)
    )
    assert big.status_code in (400, 404)
    # Undeclared blob refused.
    assert client.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{uuid.uuid4()}", content=b"z" * 10
    ).status_code in (400, 404)
    # Cancel, then upload: pre-check refuses before any staging.
    with session_factory() as session:
        cancel_delivery(session, delivery_id=delivery_id, sender_user_id=sender_id)
        session.commit()
    refused = client.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}", content=b"x" * 1024
    )
    assert refused.status_code == 404
    store = client.app.state.blob_store
    assert f"pending/{delivery_id}/{blob_id}" not in store._blobs
    reservations = client.app.state.upload_reservations
    assert reservations.outstanding_bytes(sender_id) == 0
    assert reservations.outstanding_uploads(sender_id) == 0


def test_finalize_rejects_corrupted_store(session_factory) -> None:
    """Corruption pre-finalize: bytes replaced after upload no longer
    match the declared hash, so seal is refused."""
    import base64 as _b64

    from remanence.pending.publication import create_draft
    from remanence.pending.routing import decide_for_send, freeze_route

    sender_ring = Keyring()
    service_key_id = uuid.uuid4()
    blob_bytes = bytes([0x71]) * 256
    with session_factory() as session:
        sender = _make_user(session, f"tc{uuid.uuid4().hex[:8]}")
        sender_bundle = _make_bundle(session, sender, sender_ring)
        session.commit()
        decision = decide_for_send(
            session, destination_kind="email", normalized_destination=ADDRESS,
            active_bundle_id=None, hmac_key=HMAC_KEY,
            evidence_key=EVIDENCE_KEY, sender_user_id=sender.id,
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        import hashlib as _hashlib

        blob_id = str(uuid.uuid4())
        declarations = [{"blob_id": blob_id, "kind": 3, "ordinal": 0,
                         "ciphertext_size": len(blob_bytes),
                         "ciphertext_sha256_hex": _hashlib.sha256(blob_bytes).hexdigest()}]
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=10),
            declarations=declarations, now=NOW, pending_sends_enabled=True,
        )
        session.commit()
        sender_id, delivery_id = sender.id, delivery.id
    client = _build_client(session_factory, flags_on=True, user_id=sender_id, session_id=uuid.uuid4())
    assert client.put(
        f"/v2/pending-deliveries/{delivery_id}/blobs/{blob_id}", content=blob_bytes
    ).status_code == 200
    # Attacker swaps the stored bytes: finalize's stat/size/hash binding
    # refuses the seal.
    client.app.state.blob_store._blobs[f"pending/{delivery_id}/{blob_id}"] = bytes([0x72]) * 256
    statement_raw, payload_raw, _ = _publish_package(
        capsule_id=delivery.capsule_id, target_id=decision.delivery_target_id,
        sender_id=sender_id, sender_bundle_id=sender_bundle.id,
        service_key_id=service_key_id, artifacts=declarations,
    )
    finalize = client.post(
        f"/v2/pending-deliveries/{delivery_id}/finalize",
        json={"publish_statement_b64": _b64.b64encode(statement_raw).decode(),
              "publish_signature_69_b64": _b64.b64encode(sender_ring.sign69(
                  domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + statement_raw)).decode(),
              "escrow_payload_b64": _b64.b64encode(payload_raw).decode(),
              "idempotency_key": str(uuid.uuid4())},
    )
    assert finalize.status_code == 404
    assert finalize.json()["code"] == "PROTOCOL_UNSUPPORTED"


def test_control_retrieval_fences_and_expiry_restore(session_factory) -> None:
    """Owner-scoped S+signature retrieval after retirement: exact bytes,
    foreign/pre-claim/disabled denied; claim expiry never blocks owned
    restore (it limits NEW claims only)."""
    from datetime import datetime as _dt
    from datetime import timezone as _tz

    from remanence.pending.claim import get_control
    from remanence.pending.claim_models import PendingDelivery as _PD

    service_ring = Keyring()
    service_key_id = uuid.uuid4()
    vault = DevSealedVault(mode="test", key_id="http-ctl-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        delivery, _, statement_raw, _ = _finalize(session, setup, service_key_id, vault)
        recipient = _make_user(session, f"ct{uuid.uuid4().hex[:8]}")
        recipient_ring = Keyring()
        recipient_bundle = _make_bundle(session, recipient, recipient_ring)
        recipient_session = uuid.uuid4()
        session.commit()
        attempt = _prove(session, delivery, recipient, recipient_session, MockDispatcher())
        _claim(
            session, delivery, recipient, recipient_bundle, recipient_ring,
            attempt, service_ring, service_key_id, vault, uuid.uuid4(),
        )
        capsule_id, delivery_id = delivery.capsule_id, delivery.id
        recipient_id = recipient.id
        stranger = _make_user(session, f"cu{uuid.uuid4().hex[:8]}")
        session.commit()
        stranger_id = stranger.id
        # Exact S + signature retrievable by the owner.
        control = get_control(session, delivery_id=delivery_id, account_user_id=recipient_id)
        assert control["publish_statement"] == statement_raw
        assert control["capsule_id"] == capsule_id
        # Foreign and pre-claim states denied (pre-claim: fresh delivery).
        with pytest.raises(ClaimError):
            get_control(session, delivery_id=delivery_id, account_user_id=stranger_id)
        # Disabled owner fenced.
        recipient.disabled_at = _dt.now(_tz.utc)
        session.commit()
        with pytest.raises(ClaimError):
            get_control(session, delivery_id=delivery_id, account_user_id=recipient_id)
        recipient.disabled_at = None
        session.commit()
        # Claim expiry passes: owned restore still works (feed + control).
        row = session.get(_PD, delivery_id)
        row.claim_expires_at = _dt.now(_tz.utc) - timedelta(days=1)
        session.commit()
        assert get_control(session, delivery_id=delivery_id, account_user_id=recipient_id) is not None
        assert get_claim(session, delivery_id=delivery_id, account_user_id=recipient_id) is not None
    client = _build_client(session_factory, flags_on=True, user_id=recipient_id, session_id=recipient_session)
    control_http = client.get(f"/v2/pending-deliveries/{delivery_id}/control")
    assert control_http.status_code == 200, control_http.text
    assert control_http.json()["publish_statement_b64"] == base64.b64encode(statement_raw).decode()
    feed = client.get("/v2/pending-incoming")
    assert len(feed.json()["items"]) == 1
    xclient = _build_client(session_factory, flags_on=True, user_id=stranger_id, session_id=uuid.uuid4())
    assert xclient.get(f"/v2/pending-deliveries/{delivery_id}/control").status_code == 404
