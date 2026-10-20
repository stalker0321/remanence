"""Atomic accounting across real signed v1/v2 publication on isolated PG."""

import uuid
from datetime import timedelta

import pytest
from sqlalchemy import delete, func, select
from tink import tink_config

from remanence.capsules.finalize_service import CapsuleFinalizeError
from remanence.capsules.models import CapsuleState
from remanence.pending.publication import PublicationError, cancel_delivery
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode as Mode
from remanence.send_access.service import SendAccessError, grant_lifetime, provision

from send_access_support import NOW, actor, publish, source

pytest_plugins = ("test_session_repository_create",)
ERRORS = (CapsuleFinalizeError, PublicationError, SendAccessError)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def receipts(session):
    return list(session.scalars(select(SendPublication).order_by(SendPublication.free_slot)))


@pytest.mark.parametrize("arm,self_send", [("v1", True), ("v1", False), ("v2", False)])
def test_two_slots_then_refusal_and_outer_rollback(session_factory, tmp_path, arm, self_send):
    with session_factory() as session:
        sender = actor(session)
        items = [source(session, tmp_path, arm, sender=sender,
                        recipient=sender if self_send else None) for _ in range(3)]
        session.commit()
        publish(session, items[0])
        # Service has not committed publication or slot.
        with session_factory() as observer:
            assert receipts(observer) == []
            assert items[0].row(observer).state == "DRAFT"
        session.rollback()
        assert receipts(session) == []
        assert items[0].row(session).state == "DRAFT"
        for item in items[:2]:
            publish(session, item)
            session.commit()
        with pytest.raises(ERRORS) as error:
            publish(session, items[2])
        assert error.value.code == "SEND_LIMIT_REACHED"
        session.rollback()
        assert items[2].row(session).state == "DRAFT"
        rows = receipts(session)
        assert [row.free_slot for row in rows] == [1, 2]
        assert {row.bucket for row in rows} == ({"SELF"} if self_send else {"OTHER"})
        assert all(row.policy_version == 1 and row.funding == "FREE" for row in rows)


def test_self_and_other_have_independent_two_slots(session_factory, tmp_path):
    with session_factory() as session:
        sender = actor(session)
        items = [source(session, tmp_path, "v1", sender=sender, recipient=sender) for _ in range(3)]
        others = [source(session, tmp_path, arm, sender=sender) for arm in ("v1", "v2", "v2")]
        session.commit()
        for item in items[:2] + others[:2]:
            publish(session, item)
            session.commit()
        for item in (items[2], others[2]):
            with pytest.raises(ERRORS) as error:
                publish(session, item)
            assert error.value.code == "SEND_LIMIT_REACHED"
            session.rollback()
        assert sorted((row.bucket, row.free_slot) for row in receipts(session)) == [
            ("OTHER", 1), ("OTHER", 2), ("SELF", 1), ("SELF", 2),
        ]


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_replay_modes_and_lifetime_preserve_original_receipt(session_factory, tmp_path, arm):
    with session_factory() as session:
        sender = actor(session)
        items = [source(session, tmp_path, arm, sender=sender) for _ in range(6)]
        session.commit()
        publish(session, items[0], mode=Mode.OFF)
        session.commit()
        assert items[0].row(session).send_accounting_version is None
        publish(session, items[0])  # Old NULL marker replay under ENFORCE.
        session.commit()
        assert receipts(session) == []
        publish(session, items[1], mode=Mode.RECORD)
        session.commit()
        assert receipts(session)[0].funding == "BETA_EXEMPT"
        for item in items[2:4]:
            publish(session, item)
            session.commit()
        for item in items[:4]:
            publish(session, item, clock=lambda: NOW + timedelta(days=1000))
            session.commit()
        assert len(receipts(session)) == 3
        key = f"synthetic-{uuid.uuid4()}"
        grant = grant_lifetime(session, account_id=sender.user.id, grant_key=key, origin="test", now=NOW)
        session.commit()
        assert grant_lifetime(session, account_id=sender.user.id, grant_key=key, origin="test").lifetime_granted_at == NOW
        for item in items[4:]:
            publish(session, item)
            session.commit()
        assert sorted(row.funding for row in receipts(session)) == [
            "BETA_EXEMPT", "FREE", "FREE", "LIFETIME", "LIFETIME",
        ]
        provision(session, sender.user.id, now=NOW + timedelta(days=1))
        session.commit()
        assert session.get(AccountSendAccess, sender.user.id).lifetime_granted_at == grant.lifetime_granted_at
        assert len(receipts(session)) == 5


@pytest.mark.parametrize("arm", ["v1", "v2"])
@pytest.mark.parametrize("corruption", ["missing", "hash", "source", "owner", "null_marker"])
def test_accounted_replay_fails_closed_even_in_off(session_factory, tmp_path, arm, corruption):
    with session_factory() as session:
        sender = actor(session)
        other = actor(session)
        item = source(session, tmp_path, arm, sender=sender)
        session.commit()
        publish(session, item)
        session.commit()
        receipt = session.get(SendPublication, item.capsule_id)
        if corruption == "missing":
            session.delete(receipt)
        elif corruption == "hash":
            receipt.publication_sha256 = b"x" * 32
        elif corruption == "owner":
            receipt.account_id = other.user.id
        elif corruption == "source":
            alternate = source(session, tmp_path, "v2", sender=sender)
            receipt.v1_capsule_id = None
            receipt.pending_delivery_id = alternate.id
            receipt.recipient_user_id_at_publish = None
            receipt.classification = "UNRESOLVED_AT_PUBLISH"
        else:
            item.row(session).send_accounting_version = None
        session.commit()  # Deliberate synthetic corruption, not a repair API.
        with pytest.raises(ERRORS) as error:
            publish(session, item, mode=Mode.OFF)
        assert error.value.code == "SEND_ACCESS_UNAVAILABLE"
        session.rollback()


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_conflicting_replay_and_missing_access_do_not_grant(session_factory, tmp_path, arm):
    with session_factory() as session:
        sender = actor(session, provisioned=False)
        item = source(session, tmp_path, arm, sender=sender)
        session.commit()
        with pytest.raises(ERRORS) as error:
            publish(session, item)
        assert error.value.code == "SEND_ACCESS_UNAVAILABLE"
        session.rollback()
        assert session.get(AccountSendAccess, sender.user.id) is None
        provision(session, sender.user.id, now=NOW)
        session.commit()
        publish(session, item)
        session.commit()
        if arm == "v1":
            item.kwargs["signature"] = b"x" * 69
        else:
            item.kwargs["operation_id"] = uuid.uuid4()
        with pytest.raises(ERRORS) as error:
            publish(session, item)
        assert error.value.code == ("FINALIZE_CONFLICT" if arm == "v1" else "IDEMPOTENCY_CONFLICT")
        session.rollback()
        assert len(receipts(session)) == 1


@pytest.mark.parametrize("first_arm", ["v1", "v2"])
def test_published_opposite_arm_collision_including_legacy(session_factory, tmp_path, first_arm):
    with session_factory() as session:
        sender = actor(session)
        v1 = source(session, tmp_path, "v1", sender=sender)
        v2 = source(session, tmp_path, "v2", sender=sender, capsule_id=v1.capsule_id)
        session.commit()
        first, second = (v1, v2) if first_arm == "v1" else (v2, v1)
        publish(session, first, mode=Mode.OFF)
        session.commit()
        with pytest.raises(ERRORS) as error:
            publish(session, second)
        assert error.value.code == "SEND_ACCESS_UNAVAILABLE"
        session.rollback()
        assert second.row(session).state == "DRAFT"
        assert receipts(session) == []


def test_lifetime_conflicts_and_caller_rollback(session_factory):
    with session_factory() as session:
        a, b = actor(session), actor(session)
        session.commit()
        grant_lifetime(session, account_id=a.user.id, grant_key="test-key", origin="test", now=NOW)
        session.rollback()
        assert session.get(AccountSendAccess, a.user.id).lifetime_granted_at is None
        grant_lifetime(session, account_id=a.user.id, grant_key="test-key", origin="test", now=NOW)
        session.commit()
        for account_id, key, origin in (
            (a.user.id, "different", "test"), (a.user.id, "test-key", "different"),
            (b.user.id, "test-key", "test"),
        ):
            with pytest.raises(SendAccessError) as error:
                grant_lifetime(session, account_id=account_id, grant_key=key, origin=origin)
            assert error.value.code == "LIFETIME_GRANT_CONFLICT"
            session.rollback()
        assert session.get(AccountSendAccess, b.user.id).lifetime_granted_at is None
