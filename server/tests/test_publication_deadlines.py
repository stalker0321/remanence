"""T373 deadline regressions: real PG, synthetic identities and actual signed bytes."""

import hashlib
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select
from tink import tink_config

from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.finalize_service import CapsuleFinalizeError, CapsuleFinalizeService
from remanence.capsules.models import Capsule, CapsuleState
from remanence.pending import domains
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.models import GiftLocator
from remanence.pending.publication import PublicationError, finalize_delivery
from remanence.pending.vault import DevSealedVault
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

from test_capsule_finalize_service import _protocol_blobs, _ready_world, _sign, _statement_bytes
from test_pending_m3_pg import (
    DEV_VAULT_KEY, HMAC_KEY, LOCATOR_CODE_KEY, NOW, _publish_package, _setup_pending,
)

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def pending_request(setup, *, signed_deadline=None, created_at=NOW):
    delivery = setup["delivery"]
    service_id = uuid.uuid4()
    raw, escrow_raw, _ = _publish_package(
        capsule_id=delivery.capsule_id,
        target_id=delivery.delivery_target_id,
        sender_id=setup["sender"].id,
        sender_bundle_id=setup["sender_bundle"].id,
        service_key_id=service_id,
        artifacts=setup["declarations"],
    )
    statement = dv2.PendingPublishStatementV2.FromString(raw)
    statement.created_at_epoch_seconds = int(created_at.timestamp())
    statement.claim_expires_at_epoch_seconds = int(
        (signed_deadline or delivery.claim_expires_at).timestamp()
    )
    raw = statement.SerializeToString(deterministic=True)
    escrow = dv2.PendingEscrowPlaintextV1.FromString(escrow_raw)
    escrow.publish_statement_sha256 = hashlib.sha256(raw).digest()
    escrow.claim_expires_at_epoch_seconds = statement.claim_expires_at_epoch_seconds
    return dict(
        delivery_id=delivery.id,
        principal_user_id=setup["sender"].id,
        statement_raw=raw,
        signature_69=setup["sender_ring"].sign69(
            domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + raw
        ),
        escrow_payload_raw=escrow.SerializeToString(deterministic=True),
        vault=DevSealedVault(mode="test", key_id="t373-deadline", key_32=DEV_VAULT_KEY),
        expected_service_key_id=service_id,
        locator_hmac_key=HMAC_KEY,
        locator_code_key_32=LOCATOR_CODE_KEY,
        locator_code_key_mode="test",
        hint_mask="synthetic",
        operation_id=uuid.uuid4(),
        blob_stat=lambda _: (505, "55" * 32),
    )


def assert_pending_rollback(session, delivery_id):
    session.rollback()
    row = session.get(PendingDelivery, delivery_id)
    assert row.state == "DRAFT"
    assert row.publish_statement is None
    assert row.vault_ciphertext is None
    assert session.scalar(select(func.count()).select_from(GiftLocator)) == 0


def v1_request(world):
    return dict(
        authenticated_sender_user_id=world["sender"].id,
        capsule_id=world["capsule"].id,
        statement=world["statement"],
        signature=world["signature"],
        sender_key_bundle_id=world["sender_bundle"].id,
        envelope=world["envelope"],
    )


def test_pending_rejects_signed_deadline_different_from_stored(session_factory):
    with session_factory() as session:
        setup = _setup_pending(session)
        request = pending_request(
            setup, signed_deadline=setup["delivery"].claim_expires_at - timedelta(days=1)
        )
        with pytest.raises(PublicationError) as error:
            finalize_delivery(session, **request, now=NOW)
        assert error.value.code == "PROTOCOL_UNSUPPORTED"
        assert_pending_rollback(session, request["delivery_id"])


def test_pending_rejects_expired_claim_with_stale_request_time(session_factory):
    with session_factory() as session:
        setup = _setup_pending(session)
        deadline = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(seconds=1)
        setup["delivery"].claim_expires_at = deadline
        session.commit()
        request = pending_request(setup, created_at=deadline - timedelta(days=1))
        with pytest.raises(PublicationError) as error:
            finalize_delivery(session, **request, now=deadline - timedelta(seconds=1))
        assert error.value.code == "GIFT_UNAVAILABLE"
        assert_pending_rollback(session, request["delivery_id"])


def test_v1_rejects_expired_draft_with_stale_request_time(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule = world["capsule"]
        deadline = datetime.now(timezone.utc) - timedelta(seconds=1)
        capsule.created_at = deadline - timedelta(days=1)
        capsule.draft_expires_at = deadline
        session.commit()
        world["statement"] = _statement_bytes(capsule, _protocol_blobs(session, capsule))
        world["signature"] = _sign(world["sender_private"], world["statement"])
        with pytest.raises(CapsuleFinalizeError) as error:
            CapsuleFinalizeService(session, world["store"]).finalize(
                **v1_request(world), now=deadline - timedelta(seconds=1)
            )
        assert error.value.code == "DRAFT_EXPIRED"
        session.rollback()
        assert session.get(Capsule, capsule.id).state is CapsuleState.DRAFT
        assert session.get(CapsuleEnvelope, capsule.id) is None
