"""Real outer rollback: staged source, marker, locator and receipt all disappear."""

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from tink import tink_config

from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.finalize_service import CapsuleFinalizeService, LocatorIssuance
from remanence.capsules.models import Capsule
from remanence.pending import locator_store, publication
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.models import GiftLocator
from remanence.pending.vault import VaultError
from remanence.send_access import service
from remanence.send_access.models import SendPublication
from test_pending_m3_pg import HMAC_KEY, LOCATOR_CODE_KEY
from test_publication_deadlines import Clock
from send_access_support import NOW, actor, publish, source

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def assert_rolled_back(factory, item):
    with factory() as check:
        row = item.row(check)
        assert row.state == "DRAFT" and row.send_accounting_version is None
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 0
        assert check.scalar(select(func.count()).select_from(GiftLocator)) == 0
        if item.arm == "v1":
            assert row.signed_statement is None
            assert check.get(CapsuleEnvelope, item.id) is None
        else:
            assert row.publish_statement is None and row.vault_ciphertext is None


@pytest.mark.parametrize("arm", ["v1", "v2"])
@pytest.mark.parametrize("stage", ["source_flush", "receipt_flush", "after_receipt", "late_deadline"])
def test_outer_rollback_at_publication_boundaries(session_factory, tmp_path, monkeypatch, arm, stage):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, arm, sender=sender)
        deadline = item.row(session).draft_expires_at if arm == "v1" else item.row(session).claim_expires_at
        session.commit()
        clock = Clock(NOW)
        original_flush = session.flush
        original_record = service.record
        hit = []

        def failing_flush(*args, **kwargs):
            ready_source = any(isinstance(row, (Capsule, PendingDelivery)) and row.state in ("READY", "PENDING")
                               for row in session.dirty)
            new_receipt = any(isinstance(row, SendPublication) for row in session.new)
            if ((stage == "source_flush" and ready_source) or (stage == "receipt_flush" and new_receipt)):
                hit.append(stage)
                raise SQLAlchemyError("synthetic flush failure")
            return original_flush(*args, **kwargs)

        def delayed_record(*args, **kwargs):
            result = original_record(*args, **kwargs)
            if stage == "after_receipt":
                hit.append(stage)
                raise service.SendAccessError()
            if stage == "late_deadline":
                hit.append(stage)
                clock.current = deadline
            return result

        monkeypatch.setattr(session, "flush", failing_flush)
        monkeypatch.setattr(service, "record", delayed_record)
        with pytest.raises(Exception):
            publish(session, item, clock=clock)
        assert hit == [stage]
        if arm == "v1" and stage == "source_flush":
            # Restore path must include marker, even before caller rollback.
            assert item.row(session).state == "DRAFT"
            assert item.row(session).send_accounting_version is None
        session.rollback()
        assert_rolled_back(session_factory, item)
        monkeypatch.setattr(session, "flush", original_flush)
        monkeypatch.setattr(service, "record", original_record)
        publish(session, item)
        session.commit()
        assert session.get(SendPublication, item.capsule_id).free_slot == 1


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_optional_locator_degrades_but_mandatory_locator_rolls_back(session_factory, tmp_path, monkeypatch, arm):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, arm, sender=sender)
        session.commit()
        original = locator_store.allocate_reserved

        def allocate_then_fail(*args, **kwargs):
            assert not any(isinstance(row, SendPublication) for row in session.new)
            original(*args, **kwargs)  # Real locator insert inside savepoint / outer transaction.
            raise RuntimeError("synthetic locator failure")

        monkeypatch.setattr(locator_store, "allocate_reserved", allocate_then_fail)
        monkeypatch.setattr(publication, "allocate_reserved", allocate_then_fail)
        if arm == "v1":
            result = publish(session, item, locator_issuance=LocatorIssuance(HMAC_KEY, LOCATOR_CODE_KEY, "test"))
            session.commit()
            assert result.locator_code is None
            assert item.row(session).state == "READY" and item.row(session).send_accounting_version == 1
            assert session.get(SendPublication, item.capsule_id).free_slot == 1
            assert session.scalar(select(func.count()).select_from(GiftLocator)) == 0
        else:
            with pytest.raises(RuntimeError):
                publish(session, item)
            session.rollback()
            assert_rolled_back(session_factory, item)


def test_vault_failure_keeps_slot_and_source_unpublished(session_factory, tmp_path, monkeypatch):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, "v2", sender=sender)
        session.commit()

        def fail(*args):
            raise VaultError("synthetic")

        monkeypatch.setattr(item.kwargs["vault"], "seal", fail)
        with pytest.raises(publication.PublicationError) as error:
            publish(session, item)
        assert error.value.code == "PROVIDER_UNAVAILABLE"
        session.rollback()
        assert_rolled_back(session_factory, item)
