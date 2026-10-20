"""T376 amendment: actual SQL order plus forced crossed-send completion orders."""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event

import pytest
from sqlalchemy import event, func, select
from tink import tink_config

from remanence.capsules.finalize_service import LocatorIssuance
from remanence.pending import locator_store
from remanence.send_access import service
from remanence.send_access.models import AccountSendAccess, SendPublication
from send_access_support import NOW, actor, publish, source
from test_pending_m3_pg import HMAC_KEY, LOCATOR_CODE_KEY
from test_send_access_publication import ERRORS
from test_send_access_races import worker

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


@pytest.mark.parametrize("arm,locator", [("v1", "none"), ("v1", "success"), ("v1", "degrade"), ("v2", "mandatory")])
def test_first_publication_sql_contains_marker_and_record_never_updates_source(
    session_factory, tmp_path, monkeypatch, arm, locator,
):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, arm, sender=sender)
        session.commit()
        sql = []
        connection = session.connection()

        def observe(connection, cursor, statement, parameters, context, executemany):
            sql.append((statement.lower(), parameters))

        event.listen(connection, "before_cursor_execute", observe)
        original_allocate = locator_store.allocate_reserved

        def allocate(*args, **kwargs):
            assert not any(isinstance(row, SendPublication) for row in session.new)
            if arm == "v2":
                assert item.row(session).state == "DRAFT"
                assert item.row(session).send_accounting_version is None
            result = original_allocate(*args, **kwargs)
            if locator == "degrade":
                raise RuntimeError("synthetic optional locator failure")
            return result

        monkeypatch.setattr(locator_store, "allocate_reserved", allocate)
        from remanence.pending import publication
        monkeypatch.setattr(publication, "allocate_reserved", allocate)
        extra = {} if arm == "v2" or locator == "none" else {
            "locator_issuance": LocatorIssuance(HMAC_KEY, LOCATOR_CODE_KEY, "test"),
        }
        publish(session, item, **extra)
        session.commit()
        name = "capsules" if arm == "v1" else "pending_deliveries"
        source_updates = [(statement, parameters) for statement, parameters in sql if statement.startswith(f"update {name} ")]
        assert len(source_updates) == 1
        statement, parameters = source_updates[0]
        assert "send_accounting_version=" in statement and "state=" in statement
        assert parameters["send_accounting_version"] == 1
        print("actual publication UPDATE, parameters omitted:", statement)
        receipt_index = next(index for index, (statement, _) in enumerate(sql) if statement.startswith("insert into send_publications "))
        source_index = next(index for index, (statement, _) in enumerate(sql) if statement.startswith(f"update {name} "))
        assert receipt_index > source_index
        assert all(index < receipt_index for index, (statement, _) in enumerate(sql)
                   if "gift_locators" in statement and statement.startswith(("insert", "update")))


@pytest.mark.parametrize("first_index", [0, 1])
def test_crossed_accounts_both_controlled_receipt_orders(session_factory, tmp_path, monkeypatch, first_index):
    with session_factory() as seed:
        a, b = actor(seed), actor(seed)
        items = [source(seed, tmp_path, "v1", sender=a, recipient=b),
                 source(seed, tmp_path, "v1", sender=b, recipient=a)]
        seed.commit()
    locked_recipients = Barrier(2)
    first_recorded = Event()
    lock_access, record = service._lock_access, service.record

    def lock_after_both_recipients(session, account_id):
        locked_recipients.wait(timeout=3)
        return lock_access(session, account_id)

    def ordered_record(session, row, allocation, **kwargs):
        if row.id != items[first_index].id:
            assert first_recorded.wait(3), "first writer could not flush receipt with opposing recipient lock held"
        record(session, row, allocation, **kwargs)
        if row.id == items[first_index].id:
            first_recorded.set()

    monkeypatch.setattr(service, "_lock_access", lock_after_both_recipients)
    monkeypatch.setattr(service, "record", ordered_record)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, session_factory, item, [], Event()) for item in items]
        assert [future.result(timeout=8) for future in futures] == ["ok", "ok"]
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 2


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_record_rejects_bad_marker_without_repair(session_factory, tmp_path, monkeypatch, arm):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, arm, sender=sender)
        session.commit()
        original = service.record

        def corrupt_marker(session, row, allocation, **kwargs):
            row.send_accounting_version = None
            return original(session, row, allocation, **kwargs)

        monkeypatch.setattr(service, "record", corrupt_marker)
        with pytest.raises(ERRORS) as error:
            publish(session, item)
        assert error.value.code == "SEND_ACCESS_UNAVAILABLE"
        session.rollback()
        assert item.row(session).state == "DRAFT" and item.row(session).send_accounting_version is None
        assert session.scalar(select(func.count()).select_from(SendPublication)) == 0


def test_same_lifetime_key_concurrent_different_accounts_has_one_owner(session_factory, monkeypatch):
    with session_factory() as seed:
        accounts = [actor(seed).user.id for _ in range(2)]
        seed.commit()
    both_access_locked = Barrier(2)
    original = service._lock_access

    def lock(session, account_id):
        row = original(session, account_id)
        both_access_locked.wait(timeout=3)
        return row

    monkeypatch.setattr(service, "_lock_access", lock)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, session_factory, None, [], Event(), action=lambda session, account_id=account_id:
                               service.grant_lifetime(session, account_id=account_id, grant_key="synthetic-shared", origin="test", now=NOW))
                   for account_id in accounts]
        assert sorted(future.result(timeout=8) for future in futures) == ["LIFETIME_GRANT_CONFLICT", "ok"]
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(AccountSendAccess).where(
            AccountSendAccess.lifetime_granted_at.is_not(None))) == 1
