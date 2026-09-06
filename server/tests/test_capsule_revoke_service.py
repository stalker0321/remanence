"""PostgreSQL tests for sender capsule revocation."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier
from typing import Any
from uuid import uuid4

import pytest
import tink
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from tink import tink_config

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob
from remanence.capsules.delivery_models import RecipientDeliveryState
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.finalize_service import CapsuleFinalizeError
from remanence.capsules.models import Capsule, CapsuleState, RecipientTombstoneCounter
from remanence.capsules.revoke_service import (
    CapsuleRevokeError,
    CapsuleRevokeResult,
    CapsuleRevokeService,
)

from test_capsule_abort_service import _add_draft, _seed_user
from test_capsule_finalize_service import (
    _NOW,
    _finalize,
    _ready_world,
)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _assert_error(call, code: str) -> CapsuleRevokeError:
    with pytest.raises(CapsuleRevokeError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule revoke failed"
    assert repr(caught.value) == f"CapsuleRevokeError(code={code!r})"
    assert caught.value.__cause__ is None
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("revoke service must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _make_ready(session, tmp_path):
    world = _ready_world(session, tmp_path)
    _finalize(
        session,
        world["store"],
        sender_id=world["sender"].id,
        capsule=world["capsule"],
        statement=world["statement"],
        signature_bytes=world["signature"],
        envelope=world["envelope"],
    )
    session.commit()
    session.refresh(world["capsule"])
    return world


def _revoke(session, world, *, now=_NOW) -> CapsuleRevokeResult:
    return CapsuleRevokeService(session).revoke(
        authenticated_sender_user_id=world["sender"].id,
        capsule_id=world["capsule"].id,
        now=now,
    )


def _add_direct_ready(session, *, sender, sender_bundle, recipient, recipient_bundle) -> Capsule:
    capsule = Capsule(
        id=uuid4(),
        sender_user_id=sender.id,
        recipient_user_id=recipient.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=recipient_bundle.id,
        protocol_version=1,
        state=CapsuleState.READY,
        signed_statement=b"signed-statement",
        signed_statement_sha256=b"\x01" * 32,
        publish_signature=b"\x02" * 69,
        created_at=_NOW,
        ready_at=_NOW,
        draft_expires_at=_NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    return capsule


def test_revoke_ready_capsule_preserves_tombstone_material_and_delivery_rows(
    session_factory, tmp_path, monkeypatch
):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        capsule_id = world["capsule"].id
        recipient_id = world["recipient"].id
        before_envelope = session.get(CapsuleEnvelope, capsule_id)
        before_delivery = session.get(RecipientDeliveryState, (recipient_id, capsule_id))
        before_blobs = tuple(
            session.scalars(
                select(CapsuleBlob)
                .where(CapsuleBlob.capsule_id == capsule_id)
                .order_by(CapsuleBlob.id)
            )
        )
        assert before_envelope is not None
        assert before_delivery is not None
        _forbid_commit_rollback(session, monkeypatch)
        result = _revoke(session, world)
        monkeypatch.undo()
        session.commit()

        assert isinstance(result, CapsuleRevokeResult)
        assert result.capsule_id == capsule_id
        assert result.state is CapsuleState.REVOKED
        assert result.is_replay is False
        capsule = session.get(Capsule, capsule_id)
        assert capsule is not None
        assert capsule.state is CapsuleState.REVOKED
        assert session.get(CapsuleEnvelope, capsule_id).ciphertext == before_envelope.ciphertext
        after_delivery = session.get(RecipientDeliveryState, (recipient_id, capsule_id))
        assert after_delivery is not None
        assert after_delivery.publication_sequence == before_delivery.publication_sequence
        after_blobs = tuple(
            session.scalars(
                select(CapsuleBlob)
                .where(CapsuleBlob.capsule_id == capsule_id)
                .order_by(CapsuleBlob.id)
            )
        )
        assert [blob.id for blob in after_blobs] == [blob.id for blob in before_blobs]
        assert [blob.object_key for blob in after_blobs] == [blob.object_key for blob in before_blobs]


def test_double_revoke_is_idempotent_and_replay_ignores_expired_window(
    session_factory, tmp_path
):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        first = _revoke(session, world)
        session.commit()
        first_tombstone = session.get(Capsule, world["capsule"].id)
        assert first_tombstone is not None
        first_fields = (first_tombstone.tombstone_sequence, first_tombstone.revoked_at)
        assert first_fields[0] == 1
        assert first_fields[1] == _NOW
        replay = _revoke(session, world, now=_NOW + timedelta(days=3))
        session.commit()
        assert (first.is_replay, replay.is_replay) == (False, True)
        replayed_tombstone = session.get(Capsule, world["capsule"].id)
        assert replayed_tombstone is not None
        assert (replayed_tombstone.tombstone_sequence, replayed_tombstone.revoked_at) == first_fields
        counter = session.get(RecipientTombstoneCounter, world["recipient"].id)
        assert counter is not None and counter.last_sequence == 1


def test_revoke_window_allows_inside_and_exact_boundary_but_rejects_after(
    session_factory, tmp_path
):
    for now, expected in (
        (_NOW + timedelta(hours=23, minutes=59, seconds=59), "ok"),
        (_NOW + timedelta(hours=24), "ok"),
        (_NOW + timedelta(hours=24, microseconds=1), "WINDOW_EXPIRED"),
    ):
        with session_factory() as session:
            world = _make_ready(session, tmp_path)
            if expected == "ok":
                result = _revoke(session, world, now=now)
                session.commit()
                assert result.is_replay is False
                assert session.get(Capsule, world["capsule"].id).state is CapsuleState.REVOKED
            else:
                _assert_error(lambda: _revoke(session, world, now=now), expected)
                session.rollback()
                assert session.get(Capsule, world["capsule"].id).state is CapsuleState.READY


def test_draft_and_aborted_capsules_are_not_revocable(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        draft = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            with_idempotency=False,
        )
        aborted = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            with_idempotency=False,
        )
        aborted.state = CapsuleState.ABORTED
        session.commit()
        for capsule in (draft, aborted):
            _assert_error(
                lambda capsule=capsule: CapsuleRevokeService(session).revoke(
                    authenticated_sender_user_id=sender.id,
                    capsule_id=capsule.id,
                    now=_NOW,
                ),
                "CAPSULE_STATE_INVALID",
            )


def test_unknown_and_foreign_capsules_share_not_found_posture(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        _assert_error(
            lambda: CapsuleRevokeService(session).revoke(
                authenticated_sender_user_id=uuid4(),
                capsule_id=world["capsule"].id,
                now=_NOW,
            ),
            "CAPSULE_NOT_FOUND",
        )
        _assert_error(
            lambda: CapsuleRevokeService(session).revoke(
                authenticated_sender_user_id=world["sender"].id,
                capsule_id=uuid4(),
                now=_NOW,
            ),
            "CAPSULE_NOT_FOUND",
        )
        session.rollback()
        assert session.get(Capsule, world["capsule"].id).state is CapsuleState.READY


def test_invalid_inputs_fail_closed_without_database():
    service = CapsuleRevokeService(None)
    capsule_id = uuid4()
    sender_id = uuid4()
    _assert_error(
        lambda: service.revoke(
            authenticated_sender_user_id=str(sender_id),  # type: ignore[arg-type]
            capsule_id=capsule_id,
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.revoke(
            authenticated_sender_user_id=sender_id,
            capsule_id=capsule_id,
            now=_NOW.replace(tzinfo=None),
        ),
        "VALIDATION_FAILED",
    )


def test_revoke_flush_failure_is_redacted_and_rolls_back(session_factory, tmp_path, monkeypatch, caplog):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)

        def fail_flush(*_args: Any, **_kwargs: Any) -> None:
            raise SQLAlchemyError("secret revoke flush detail")

        monkeypatch.setattr(session, "flush", fail_flush)
        _forbid_commit_rollback(session, monkeypatch)
        with caplog.at_level("ERROR"):
            error = _assert_error(lambda: _revoke(session, world), "INTERNAL_ERROR")
        assert "secret revoke flush detail" not in f"{error!s} {error!r}"
        relevant = [record for record in caplog.records if "capsule revoke internal failure" in record.message]
        assert len(relevant) == 1
        assert relevant[0].exc_info is None
        assert relevant[0].msg == (
            "capsule revoke internal failure stage=%s capsule_id=%s exc_type=%s"
        )
        assert relevant[0].args == ("persist", world["capsule"].id, "SQLAlchemyError")
        assert "secret revoke flush detail" not in relevant[0].getMessage()
        monkeypatch.undo()
        session.rollback()
        assert session.get(Capsule, world["capsule"].id).state is CapsuleState.READY


def test_concurrent_double_revoke_has_one_transition(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        session.commit()

    barrier = Barrier(2)

    def worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleRevokeService(session).revoke(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    now=_NOW,
                )
                session.commit()
                return ("ok", result.is_replay)
            except CapsuleRevokeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(lambda _index: worker(), range(2)))

    assert sorted(outcomes) == [("ok", False), ("ok", True)]
    with session_factory() as session:
        assert session.get(Capsule, capsule_id).state is CapsuleState.REVOKED


def test_concurrent_same_recipient_revoke_sequences_are_gap_free(session_factory):
    count = 4
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsules = [
            _add_direct_ready(
                session,
                sender=sender,
                sender_bundle=sender_bundle,
                recipient=recipient,
                recipient_bundle=recipient_bundle,
            )
            for _ in range(count)
        ]
        session.commit()
        sender_id = sender.id
        capsule_ids = tuple(capsule.id for capsule in capsules)

    barrier = Barrier(count)

    def worker(capsule_id):
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleRevokeService(session).revoke(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    now=_NOW,
                )
                session.commit()
                return ("ok", result.is_replay)
            except CapsuleRevokeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=count) as executor:
        outcomes = list(executor.map(worker, capsule_ids))

    assert outcomes == [("ok", False)] * count
    with session_factory() as session:
        sequences = session.scalars(
            select(Capsule.tombstone_sequence)
            .where(Capsule.id.in_(capsule_ids))
            .order_by(Capsule.tombstone_sequence)
        ).all()
        assert sequences == list(range(1, count + 1))
        counter = session.get(RecipientTombstoneCounter, recipient.id)
        assert counter is not None and counter.last_sequence == count


def test_rollback_does_not_burn_tombstone_sequence(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_direct_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        session.commit()
        result = CapsuleRevokeService(session).revoke(
            authenticated_sender_user_id=sender.id,
            capsule_id=capsule.id,
            now=_NOW,
        )
        assert result.is_replay is False
        assert capsule.tombstone_sequence == 1
        session.rollback()
        restored = session.get(Capsule, capsule.id)
        assert restored is not None and restored.state is CapsuleState.READY
        assert restored.tombstone_sequence is None
        assert session.get(RecipientTombstoneCounter, recipient.id) is None

        result = CapsuleRevokeService(session).revoke(
            authenticated_sender_user_id=sender.id,
            capsule_id=capsule.id,
            now=_NOW,
        )
        session.commit()
        assert result.is_replay is False
        assert session.get(Capsule, capsule.id).tombstone_sequence == 1


def test_finalize_and_revoke_race_has_no_lost_update(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        session.commit()

    barrier = Barrier(2)

    def revoke_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleRevokeService(session).revoke(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    now=_NOW,
                )
                session.commit()
                return ("revoke", result.is_replay)
            except CapsuleRevokeError as error:
                session.rollback()
                return ("revoke-error", error.code)

    def finalize_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = _finalize(
                    session,
                    world["store"],
                    sender_id=sender_id,
                    capsule=world["capsule"],
                    statement=world["statement"],
                    signature_bytes=world["signature"],
                    envelope=world["envelope"],
                )
                session.commit()
                return ("finalize", result.is_replay)
            except CapsuleFinalizeError as error:
                session.rollback()
                return ("finalize-error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        revoke_outcome, finalize_outcome = executor.map(
            lambda worker: worker(), (revoke_worker, finalize_worker)
        )

    assert revoke_outcome in (("revoke", False), ("revoke-error", "CAPSULE_STATE_INVALID"))
    assert finalize_outcome in (("finalize", True), ("finalize-error", "CAPSULE_STATE_INVALID"))
    assert (revoke_outcome, finalize_outcome) in {
        (("revoke", False), ("finalize-error", "CAPSULE_STATE_INVALID")),
        (("revoke", False), ("finalize", True)),
    }
    with session_factory() as session:
        assert session.get(Capsule, capsule_id).state is CapsuleState.REVOKED
