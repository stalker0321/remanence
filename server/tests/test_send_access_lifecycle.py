"""Registration, account lifecycle and no automatic refund on real PG."""

import hashlib
import uuid
from datetime import timedelta

import pytest
from sqlalchemy import func, select
from tink import tink_config

from remanence.auth.account_status import AccountStatusService
from remanence.auth.google_models import GoogleFlow
from remanence.auth.session_repository import AuthSessionRepository
from remanence.capsules.first_open_service import CapsuleFirstOpenService
from remanence.capsules.revoke_service import CapsuleRevokeService
from remanence.pending.claim import ClaimError, claim_options
from remanence.pending.profiles import register_receive, set_first_handle
from remanence.pending.providers import MockDispatcher
from remanence.pending.publication import cancel_delivery
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.service import grant_lifetime
from remanence.users.models import User
from send_access_support import NOW, actor, publish, source
from test_pending_m3_pg import Keyring, SUITE, _claim, _prove
from test_registration_service import _bundles, _register, _service
from test_send_access_publication import ERRORS

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def receipt_snapshot(session, capsule_id):
    row = session.get(SendPublication, capsule_id)
    return {column.name: getattr(row, column.name) for column in SendPublication.__table__.c}


@pytest.mark.parametrize("arm", ["v1", "receive"])
@pytest.mark.parametrize("outcome", ["commit", "rollback", "late_failure"])
def test_both_registrations_are_atomic_and_unsent_user_can_be_deleted(session_factory, monkeypatch, arm, outcome):
    with session_factory() as session:
        ticket = None
        if arm == "receive":
            ticket = GoogleFlow(
                id=uuid.uuid4(), purpose="REGISTER", secret_hash=hashlib.sha256(b"synthetic-ticket").digest(),
                subject="synthetic-subject", email="receive@example.test", expires_at=NOW + timedelta(minutes=10),
            )
            session.add(ticket)
            session.commit()

        def register():
            if arm == "v1":
                return _register(_service(session), password=None, now=NOW)
            enc, signing = _bundles()
            return register_receive(
                session, ticket=ticket, key_bundle_id=uuid.uuid4(), suite=SUITE,
                protocol_version=1, encryption_public_keyset=enc, signing_public_keyset=signing,
                now=NOW, pending_protocol_enabled=True,
            )

        if outcome == "late_failure":
            def fail(*args, **kwargs):
                assert session.scalar(select(func.count()).select_from(AccountSendAccess)) == 1
                raise RuntimeError("synthetic after-provision failure")

            monkeypatch.setattr(AuthSessionRepository, "create", fail)
            with pytest.raises(RuntimeError):
                register()
            session.rollback()
        else:
            result = register()
            assert session.get(AccountSendAccess, result.user_id).policy_version == 1
            if outcome == "commit":
                session.commit()
                with session_factory() as observer:
                    assert observer.get(AccountSendAccess, result.user_id).lifetime_granted_at is None
                    assert observer.get(User, result.user_id) is not None
                # Synthetic never-sent account: new access FK must not block deletion.
                session.delete(session.get(User, result.user_id))
                session.commit()
                assert session.get(AccountSendAccess, result.user_id) is None
            else:
                session.rollback()
        with session_factory() as observer:
            assert observer.scalar(select(func.count()).select_from(User)) == 0
            assert observer.scalar(select(func.count()).select_from(AccountSendAccess)) == 0
            if ticket is not None and outcome != "commit":
                assert observer.get(GoogleFlow, ticket.id).consumed_at is None


def test_disable_enable_handle_and_reprovision_do_not_refill(session_factory, tmp_path):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, "v1", sender=sender)
        session.commit()
        publish(session, item)
        grant_lifetime(session, account_id=sender.user.id, grant_key="lifecycle-test", origin="test", now=NOW)
        session.commit()
        before = receipt_snapshot(session, item.capsule_id)
        status = AccountStatusService(AuthSessionRepository(session))
        assert status.disable(sender.user.id, NOW)
        session.commit()
        assert session.get(User, sender.user.id).disabled_at == NOW
        assert status.enable(sender.user.id)
        session.commit()
        # Model a handleless legacy profile, then call the existing first-handle service.
        sender.user.handle_normalized = None
        sender.user.handle_display = None
        session.commit()
        set_first_handle(session, user_id=sender.user.id, handle="resumedhandle")
        session.commit()
        assert receipt_snapshot(session, item.capsule_id) == before
        access = session.get(AccountSendAccess, sender.user.id)
        assert access.lifetime_granted_at == NOW and access.lifetime_grant_key == "lifecycle-test"


@pytest.mark.parametrize("action", ["revoke", "first_open"])
def test_v1_revoke_or_open_never_refunds(session_factory, tmp_path, action):
    with session_factory() as session:
        sender, recipient = actor(session), actor(session)
        item = source(session, tmp_path, "v1", sender=sender, recipient=recipient)
        session.commit()
        publish(session, item)
        session.commit()
        before = receipt_snapshot(session, item.capsule_id)
        if action == "revoke":
            CapsuleRevokeService(session).revoke(authenticated_sender_user_id=sender.user.id, capsule_id=item.id, now=NOW)
        else:
            CapsuleFirstOpenService(session).claim(authenticated_recipient_user_id=recipient.user.id, capsule_id=item.id, now=NOW)
        session.commit()
        assert receipt_snapshot(session, item.capsule_id) == before
        if action == "revoke":
            with pytest.raises(ERRORS) as error:
                publish(session, item)
            assert error.value.code == "CAPSULE_STATE_INVALID"
            session.rollback()


@pytest.mark.parametrize("action", ["cancel", "expiry", "self_claim"])
def test_pending_remains_other_and_never_refunds(session_factory, tmp_path, action):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, "v2", sender=sender)
        session.commit()
        publish(session, item)
        session.commit()
        before = receipt_snapshot(session, item.capsule_id)
        row = item.row(session)
        if action == "cancel":
            cancel_delivery(session, delivery_id=item.id, sender_user_id=sender.user.id)
            session.commit()
        elif action == "expiry":
            with pytest.raises(ClaimError) as error:
                claim_options(session, delivery_id=item.id, account_user_id=sender.user.id,
                              session_id=uuid.uuid4(), attempt_id=uuid.uuid4(), now=row.claim_expires_at)
            assert error.value.code == "PROOF_EXPIRED"
            session.rollback()
        else:
            proof = _prove(session, row, sender.user, uuid.uuid4(), MockDispatcher())
            _claim(session, row, sender.user, sender.bundle, sender.ring, proof,
                   Keyring(), item.kwargs["expected_service_key_id"], item.kwargs["vault"], uuid.uuid4(),
                   bind_alias=False)
            assert row.state == "CLAIMED"
        assert receipt_snapshot(session, item.capsule_id) == before
        assert before["bucket"] == "OTHER" and before["classification"] == "UNRESOLVED_AT_PUBLISH"
        assert before["recipient_user_id_at_publish"] is None
        if action in ("cancel", "self_claim"):
            with pytest.raises(ERRORS) as error:
                publish(session, item)
            assert error.value.code == "GIFT_UNAVAILABLE"
            session.rollback()
