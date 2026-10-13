"""T185 M3 HTTP tests: frozen DTOs on an isolated app mounting ONLY the
pending router. main.py untouched; live beta prefix never involved.
Synthetic PG + synthetic keys. DTO strictness + R15 frozen shape.
"""

from __future__ import annotations

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
    _make_bundle,
    _make_user,
    _publish_package,
)

LOCATOR_HINT = "g•••@•••"


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


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
        "vault": DevSealedVault(mode="test", key_id="http-1", key_32=DEV_VAULT_KEY),
        "service_key_id": uuid.uuid4(),
        "email_sender": _DefaultMockDispatcher(),
    }
    ctx.update(ctx_overrides or {})
    app.state.pending_ctx = ctx

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
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180), now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
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
    draft = sender_client.post(
        "/v2/pending-deliveries",
        json={"route_id": str(route_id), "capsule_id": str(capsule_id),
              "delivery_target_id": str(target_id), "channel": "email",
              "destination": ADDRESS, "salt_b64": _b64.b64encode(bytes(range(32))).decode(),
              "claim_expires_at": int((
                  datetime.now(timezone.utc) + timedelta(days=179)).timestamp()),
              "claim_policy_version": 1, "artifacts": [],
              "idempotency_key": str(uuid.uuid4())},
    )
    assert draft.status_code == 200, draft.text
    delivery_id = draft.json()["delivery_id"]
    statement_raw, payload_raw, _ = _publish_package(
        capsule_id=capsule_id, target_id=target_id, sender_id=sender_id,
        sender_bundle_id=sender_bundle.id, service_key_id=service_key_id,
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
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180), now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
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
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180), now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
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
        )
        route = freeze_route(
            session, sender_user_id=sender.id, capsule_id=uuid.uuid4(),
            destination_kind="email", normalized_destination=ADDRESS,
            destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
            decision=decision, now=NOW, pending_sends_enabled=True,
        )
        delivery = create_draft(
            session, sender_user_id=sender.id, capsule_id=route.capsule_id,
            delivery_target_id=decision.delivery_target_id, route_id=route.id,
            channel="email", claim_expires_at=NOW + timedelta(days=180), now=NOW,
            pending_sends_enabled=True,
        )
        session.commit()
        statement_raw, payload_raw, _ = _publish_package(
            capsule_id=delivery.capsule_id, target_id=delivery.delivery_target_id,
            sender_id=sender.id, sender_bundle_id=sender_bundle.id,
            service_key_id=service_key_id,
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
