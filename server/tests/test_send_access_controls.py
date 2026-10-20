"""Legacy/null operation, default-OFF, and non-paywall/security controls."""

import pytest
from pydantic import ValidationError
from sqlalchemy import func, select
from tink import tink_config

from remanence.send_access.models import SendPublication
from remanence.send_access.policy import SendAccessMode as Mode
from remanence.send_access.service import grant_lifetime
from remanence.settings import AppMode, Settings
from send_access_http_support import bearer, post, publication_client
from send_access_support import NOW, actor, publish, source
from test_send_access_publication import ERRORS

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


@pytest.mark.parametrize("app_mode", list(AppMode))
def test_default_off_and_enforce_only_in_test(monkeypatch, tmp_path, app_mode):
    monkeypatch.delenv("REMANENCE_SEND_ACCESS_MODE", raising=False)
    config = dict(mode=app_mode, database_url="postgresql+psycopg://synthetic:synthetic@127.0.0.1/unused",
                  blob_root=tmp_path)
    assert Settings(**config).send_access_mode is Mode.OFF
    assert Settings(**config, send_access_mode="record").send_access_mode is Mode.RECORD
    if app_mode is AppMode.TEST:
        assert Settings(**config, send_access_mode="enforce").send_access_mode is Mode.ENFORCE
    else:
        with pytest.raises(ValidationError):
            Settings(**config, send_access_mode="enforce")


def test_legacy_pending_null_operation_stays_conflict_without_manufacturing_receipt(session_factory, tmp_path):
    with session_factory() as session:
        item = source(session, tmp_path, "v2", sender=actor(session))
        session.commit()
        publish(session, item, mode=Mode.OFF)
        session.commit()
        row = item.row(session)
        row.finalize_operation_id = None
        row.finalize_request_digest = None
        session.commit()
        before = bytes(row.publish_statement)
        for mode in Mode:
            with pytest.raises(ERRORS) as caught:
                publish(session, item, mode=mode)
            assert caught.value.code == "IDEMPOTENCY_CONFLICT"
            session.rollback()
            assert item.row(session).send_accounting_version is None
            assert item.row(session).publish_statement == before
            assert session.get(SendPublication, item.capsule_id) is None


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_lifetime_does_not_bypass_signed_publication_validation(session_factory, tmp_path, arm):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, arm, sender=sender)
        grant_lifetime(session, account_id=sender.user.id, grant_key="synthetic-security", origin="test", now=NOW)
        session.commit()
        name = "signature" if arm == "v1" else "signature_69"
        item.kwargs[name] = item.kwargs[name][:-1] + bytes([item.kwargs[name][-1] ^ 1])
        with pytest.raises(ERRORS) as caught:
            publish(session, item)
        assert caught.value.code in ("SIGNATURE_INVALID", "PROTOCOL_UNSUPPORTED")
        session.rollback()
        assert item.row(session).state == "DRAFT"
        assert session.get(SendPublication, item.capsule_id) is None


@pytest.mark.parametrize("arm", ["v1", "v2"])
@pytest.mark.parametrize("mode", [Mode.OFF, Mode.RECORD])
def test_http_off_and_record_have_no_quota_paywall(session_factory, tmp_path, monkeypatch, arm, mode):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, arm, sender=sender)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
        for _ in range(2):
            spent = source(seed, tmp_path, "v1", sender=sender)
            seed.commit()
            publish(seed, spent)
            seed.commit()
    with publication_client(session_factory, item, monkeypatch, mode=mode) as client:
        response = post(client, item, headers)
        assert response.status_code == (201 if arm == "v1" else 200), response.text
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication).where(SendPublication.funding == "FREE")) == 2
        row = check.get(SendPublication, item.capsule_id)
        if mode is Mode.OFF:
            assert row is None and item.row(check).send_accounting_version is None
        else:
            assert row.funding == "BETA_EXEMPT" and row.free_slot is None


def test_lifetime_allows_both_exhausted_buckets_without_rewriting_free_receipts(session_factory, tmp_path):
    with session_factory() as session:
        sender = actor(session)
        for bucket in ("SELF", "OTHER"):
            for _ in range(2):
                item = source(session, tmp_path, "v1", sender=sender,
                              recipient=sender if bucket == "SELF" else None)
                session.commit()
                publish(session, item)
                session.commit()
        grant_lifetime(session, account_id=sender.user.id, grant_key="both-exhausted", origin="test", now=NOW)
        session.commit()
        for bucket in ("SELF", "OTHER"):
            for _ in range(3):
                item = source(session, tmp_path, "v1" if bucket == "SELF" else "v2", sender=sender,
                              recipient=sender if bucket == "SELF" else None)
                session.commit()
                publish(session, item)
                session.commit()
        rows = list(session.scalars(select(SendPublication)))
        assert sorted((row.bucket, row.free_slot) for row in rows if row.funding == "FREE") == [
            ("OTHER", 1), ("OTHER", 2), ("SELF", 1), ("SELF", 2),
        ]
        assert sorted(row.bucket for row in rows if row.funding == "LIFETIME") == ["OTHER"] * 3 + ["SELF"] * 3
