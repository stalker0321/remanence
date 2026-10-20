"""T373 deadline regressions: real PG, synthetic identities and actual signed bytes."""

import hashlib
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Event
from time import monotonic

import pytest
from sqlalchemy import func, select, text
from tink import tink_config

from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.delivery_models import RecipientDeliveryState
from remanence.capsules.finalize_service import CapsuleFinalizeError, CapsuleFinalizeService
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.locking import capsule_lock_key
from remanence.pending import domains
from remanence.pending import publication
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.models import GiftLocator
from remanence.pending.publication import PublicationError, finalize_delivery
from remanence.pending.vault import DevSealedVault
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2
from remanence.users.models import User

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


class Clock:
    def __init__(self, current):
        self.current = current

    def __call__(self):
        return self.current


def test_v1_uses_fresh_admission_timestamp(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        fresh = world["capsule"].created_at + timedelta(seconds=3, microseconds=123456)
        result = CapsuleFinalizeService(session, world["store"]).finalize(
            **v1_request(world), now=world["capsule"].created_at, clock=lambda: fresh,
        )
        session.commit()
        assert result.ready_at == fresh
        assert session.get(Capsule, result.capsule_id).ready_at == fresh
        delivery = session.scalar(select(RecipientDeliveryState).where(
            RecipientDeliveryState.capsule_id == result.capsule_id
        ))
        assert delivery.available_at == fresh


@pytest.mark.parametrize("at_boundary", [False, True])
def test_pending_fractional_deadline_uses_truncated_instant(session_factory, at_boundary):
    with session_factory() as session:
        setup = _setup_pending(session)
        # Keep the route valid; its usual TTL is shorter than the claim deadline.
        canonical = NOW + timedelta(seconds=3)
        setup["delivery"].claim_expires_at = canonical + timedelta(microseconds=900000)
        session.commit()
        request = pending_request(setup)
        current = canonical if at_boundary else canonical - timedelta(microseconds=1)
        if at_boundary:
            with pytest.raises(PublicationError) as error:
                finalize_delivery(session, **request, now=NOW, clock=lambda: current)
            assert error.value.code == "GIFT_UNAVAILABLE"
            assert_pending_rollback(session, request["delivery_id"])
        else:
            row, _ = finalize_delivery(session, **request, now=NOW, clock=lambda: current)
            session.commit()
            assert row.state == "PENDING"


@pytest.mark.parametrize("stage", ["storage", "locator"])
def test_v1_rechecks_after_slow_work(session_factory, tmp_path, monkeypatch, stage):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        clock = Clock(world["capsule"].created_at)
        service = CapsuleFinalizeService(session, world["store"])
        if stage == "storage":
            original = world["store"].stat

            def delayed(*args):
                result = original(*args)
                clock.current = world["capsule"].draft_expires_at
                return result

            monkeypatch.setattr(world["store"], "stat", delayed)
        else:
            original = service._maybe_issue_locator

            def delayed(*args):
                result = original(*args)
                clock.current = world["capsule"].draft_expires_at
                return result

            monkeypatch.setattr(service, "_maybe_issue_locator", delayed)
        with pytest.raises(CapsuleFinalizeError) as error:
            service.finalize(**v1_request(world), now=world["capsule"].created_at, clock=clock)
        assert error.value.code == "DRAFT_EXPIRED"
        session.rollback()
        assert session.get(Capsule, world["capsule"].id).state is CapsuleState.DRAFT
        assert session.get(CapsuleEnvelope, world["capsule"].id) is None


@pytest.mark.parametrize("stage", ["storage", "vault", "locator", "route"])
def test_pending_rechecks_after_slow_work(session_factory, monkeypatch, stage):
    with session_factory() as session:
        setup = _setup_pending(session)
        request = pending_request(setup)
        clock = Clock(NOW)
        expired = (
            setup["route"].expires_at if stage == "route"
            else setup["delivery"].claim_expires_at
        )
        if stage == "storage":
            def delayed(_):
                clock.current = expired
                return (505, "55" * 32)

            request["blob_stat"] = delayed
        elif stage in ("vault", "route"):
            original = request["vault"].seal

            def delayed(*args):
                result = original(*args)
                clock.current = expired
                return result

            monkeypatch.setattr(request["vault"], "seal", delayed)
        else:
            original = publication.allocate_reserved

            def delayed(*args, **kwargs):
                result = original(*args, **kwargs)
                clock.current = expired
                return result

            monkeypatch.setattr(publication, "allocate_reserved", delayed)
        with pytest.raises(PublicationError) as error:
            finalize_delivery(session, **request, now=NOW, clock=clock)
        assert error.value.code == ("ROUTE_STALE" if stage == "route" else "GIFT_UNAVAILABLE")
        assert_pending_rollback(session, request["delivery_id"])


def test_committed_replays_do_not_read_expired_clock(session_factory, tmp_path):
    def forbidden_clock():
        raise AssertionError("replay must not apply NEW publication deadline")

    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        request = v1_request(world)
        original = CapsuleFinalizeService(session, world["store"]).finalize(
            **request, now=world["capsule"].created_at, clock=lambda: world["capsule"].created_at
        )
        session.commit()
        replay = CapsuleFinalizeService(session, world["store"]).finalize(
            **request, now=world["capsule"].draft_expires_at + timedelta(days=1),
            clock=forbidden_clock,
        )
        assert replay.is_replay and replay.ready_at == original.ready_at
        conflicting = {**request, "signature": bytes([request["signature"][0] ^ 1]) + request["signature"][1:]}
        with pytest.raises(CapsuleFinalizeError) as error:
            CapsuleFinalizeService(session, world["store"]).finalize(
                **conflicting, now=world["capsule"].draft_expires_at + timedelta(days=1),
                clock=forbidden_clock,
            )
        assert error.value.code == "FINALIZE_CONFLICT"
        session.rollback()
        setup = _setup_pending(session)
        request = pending_request(setup)
        _, code = finalize_delivery(session, **request, now=NOW, clock=lambda: NOW)
        session.commit()
        replay, replay_code = finalize_delivery(
            session, **request, now=setup["delivery"].claim_expires_at + timedelta(days=1),
            clock=forbidden_clock,
        )
        assert replay.state == "PENDING" and replay_code == code
        with pytest.raises(PublicationError) as error:
            finalize_delivery(
                session, **{**request, "operation_id": uuid.uuid4()},
                now=setup["delivery"].claim_expires_at + timedelta(days=1),
                clock=forbidden_clock,
            )
        assert error.value.code == "IDEMPOTENCY_CONFLICT"


@pytest.mark.parametrize("lock_kind", ["v1_capsule", "v1_recipient", "pending_delivery"])
def test_deadline_crosses_real_pg_lock_wait(session_factory, tmp_path, lock_kind):
    """Independently connected writer really waits in PG; no mock lock proof."""
    with session_factory() as seed:
        if lock_kind.startswith("v1"):
            world = _ready_world(seed, tmp_path)
            request = v1_request(world)
            clock = Clock(world["capsule"].created_at)
            expired = world["capsule"].draft_expires_at
        else:
            setup = _setup_pending(seed)
            request = pending_request(setup)
            clock = Clock(NOW)
            expired = setup["delivery"].claim_expires_at
        seed.commit()
    initial_time = clock.current
    connected = Event()
    worker_pid = []

    def worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            worker_pid.append(session.scalar(select(func.pg_backend_pid())))
            connected.set()
            try:
                if lock_kind.startswith("v1"):
                    CapsuleFinalizeService(session, world["store"]).finalize(
                        **request, now=initial_time, clock=clock
                    )
                else:
                    finalize_delivery(session, **request, now=initial_time, clock=clock)
                session.commit()
                return "unexpected_publication"
            except (CapsuleFinalizeError, PublicationError) as error:
                session.rollback()
                return error.code

    with session_factory() as holder:
        if lock_kind == "v1_capsule":
            holder.execute(select(func.pg_advisory_xact_lock(capsule_lock_key(request["capsule_id"]))))
        elif lock_kind == "v1_recipient":
            holder.scalar(select(User).where(User.id == world["recipient"].id).with_for_update())
        else:
            holder.scalar(select(PendingDelivery).where(
                PendingDelivery.id == request["delivery_id"]
            ).with_for_update())
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(worker)
            try:
                assert connected.wait(3)
                wait_until = monotonic() + 3
                while not holder.scalar(text(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid=:pid AND NOT granted)"
                ), {"pid": worker_pid[0]}):
                    assert monotonic() < wait_until, "worker did not wait on a real PG lock"
                    Event().wait(0.01)
                assert not future.done()
                clock.current = expired
            finally:
                holder.rollback()
            assert future.result(timeout=5) == (
                "DRAFT_EXPIRED" if lock_kind.startswith("v1") else "GIFT_UNAVAILABLE"
            )
    with session_factory() as check:
        if lock_kind.startswith("v1"):
            assert check.get(Capsule, request["capsule_id"]).state is CapsuleState.DRAFT
            assert check.get(CapsuleEnvelope, request["capsule_id"]) is None
        else:
            assert_pending_rollback(check, request["delivery_id"])
