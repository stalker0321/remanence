"""Real independent READ COMMITTED transactions; bounded waits, no mock locks."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier, Event
from time import monotonic

import pytest
from sqlalchemy import func, select, text
from tink import tink_config

from remanence.send_access import service
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode
from test_publication_deadlines import Clock
from test_send_access_publication import ERRORS
from send_access_support import NOW, actor, publish, source

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def worker(factory, item, pid, connected, *, clock=lambda: NOW, action=None):
    with factory() as session:
        session.execute(text("SET LOCAL lock_timeout = '5s'"))
        assert session.scalar(text("SHOW transaction_isolation")) == "read committed"
        pid.append(session.scalar(select(func.pg_backend_pid())))
        connected.set()
        try:
            result = action(session) if action is not None else publish(session, item, clock=clock)
            session.commit()
            return "ok"
        except ERRORS as error:
            session.rollback()
            cause = error
            seen = set()
            while cause is not None and id(cause) not in seen:
                seen.add(id(cause))
                original = getattr(cause, "orig", None)
                if original is not None:
                    diagnostic = getattr(original, "diag", None)
                    print("synthetic PG failure:", getattr(original, "sqlstate", None),
                          getattr(diagnostic, "message_primary", None),
                          getattr(diagnostic, "message_detail", None),
                          getattr(diagnostic, "context", None))
                    print("synthetic SQL template (no parameters):", getattr(cause, "statement", None))
                cause = cause.__cause__ or cause.__context__
            return error.code


def wait_for_pg_lock(observer, pid, connected):
    assert connected.wait(3)
    deadline = monotonic() + 3
    while not observer.scalar(text(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid=:pid AND NOT granted)"
    ), {"pid": pid[0]}):
        assert monotonic() < deadline, "expected actual PG lock wait was not observed"
        Event().wait(0.01)


@pytest.mark.parametrize("arms", [("v1", "v1"), ("v2", "v2"), ("v1", "v2"), ("v2", "v1")])
@pytest.mark.parametrize("first_commits", [True, False])
def test_last_free_slot_commit_or_rollback(session_factory, tmp_path, arms, first_commits):
    with session_factory() as seed:
        sender = actor(seed)
        used = source(seed, tmp_path, "v1", sender=sender)
        first = source(seed, tmp_path, arms[0], sender=sender)
        second = source(seed, tmp_path, arms[1], sender=sender)
        seed.commit()
        publish(seed, used)
        seed.commit()
    with session_factory() as holder, ThreadPoolExecutor(max_workers=1) as pool:
        publish(holder, first)  # Slot2 + source are staged, not committed.
        pid, connected = [], Event()
        future = pool.submit(worker, session_factory, second, pid, connected)
        try:
            wait_for_pg_lock(holder, pid, connected)
            assert not future.done()
            if first_commits:
                holder.commit()
        finally:
            holder.rollback()
        assert future.result(timeout=5) == ("SEND_LIMIT_REACHED" if first_commits else "ok")
    with session_factory() as check:
        rows = list(check.scalars(select(SendPublication)))
        assert len(rows) == 2 and {row.free_slot for row in rows} == {1, 2}
        assert (first.row(check).state == "DRAFT") is (not first_commits)
        assert (second.row(check).state == "DRAFT") is first_commits


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_same_source_concurrent_exact_retry_is_one_receipt(session_factory, tmp_path, arm):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, arm, sender=sender)
        seed.commit()
    with session_factory() as holder, ThreadPoolExecutor(max_workers=1) as pool:
        publish(holder, item)
        pid, connected = [], Event()
        future = pool.submit(worker, session_factory, item, pid, connected)
        try:
            wait_for_pg_lock(holder, pid, connected)
            holder.commit()
        finally:
            holder.rollback()
        assert future.result(timeout=5) == "ok"
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 1


@pytest.mark.parametrize("layout", ["crossed_accounts", "self_other"])
def test_recipient_before_access_order_has_no_new_deadlock(
    session_factory, tmp_path, monkeypatch, layout,
):
    with session_factory() as seed:
        a, b = actor(seed), actor(seed)
        first = source(seed, tmp_path, "v1", sender=a, recipient=b)
        second = source(seed, tmp_path, "v1", sender=b if layout == "crossed_accounts" else a, recipient=a)
        seed.commit()
    both_recipient_locks = Barrier(2)
    original = service._lock_access

    def after_recipient(session, account_id):
        both_recipient_locks.wait(timeout=3)
        return original(session, account_id)  # Actual PG FOR UPDATE + real FK flushes.

    monkeypatch.setattr(service, "_lock_access", after_recipient)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, session_factory, item, [], Event()) for item in (first, second)]
        assert [future.result(timeout=8) for future in futures] == ["ok", "ok"]
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 2


def test_crossed_accounts_off_control_preserves_existing_lock_behavior(
    session_factory, tmp_path, monkeypatch,
):
    """Same real recipient-lock overlap, but no accounting/second marker UPDATE."""
    from remanence.capsules.finalize_service import CapsuleFinalizeService

    with session_factory() as seed:
        a, b = actor(seed), actor(seed)
        items = [
            source(seed, tmp_path, "v1", sender=a, recipient=b),
            source(seed, tmp_path, "v1", sender=b, recipient=a),
        ]
        seed.commit()
    both_recipient_locks = Barrier(2)
    original = CapsuleFinalizeService._next_publication_sequence

    def synchronized(service_instance, recipient_id):
        sequence = original(service_instance, recipient_id)
        both_recipient_locks.wait(timeout=3)
        return sequence

    monkeypatch.setattr(CapsuleFinalizeService, "_next_publication_sequence", synchronized)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [
            pool.submit(
                worker, session_factory, item, [], Event(),
                action=lambda session, item=item: publish(session, item, mode=SendAccessMode.OFF),
            )
            for item in items
        ]
        assert [future.result(timeout=8) for future in futures] == ["ok", "ok"]
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 0
        assert all(item.row(check).state == "READY" for item in items)


def test_same_recipient_publications_commit_with_distinct_sequence(session_factory, tmp_path):
    from remanence.capsules.delivery_models import RecipientDeliveryState
    from remanence.users.models import User

    with session_factory() as seed:
        a, b, recipient = actor(seed), actor(seed), actor(seed)
        items = [source(seed, tmp_path, "v1", sender=sender, recipient=recipient) for sender in (a, b)]
        seed.commit()
    with session_factory() as holder, ThreadPoolExecutor(max_workers=2) as pool:
        holder.scalar(select(User).where(User.id == recipient.user.id).with_for_update())
        workers = [([], Event()) for _ in items]
        futures = [pool.submit(worker, session_factory, item, pid, event)
                   for item, (pid, event) in zip(items, workers)]
        try:
            for pid, event in workers:
                wait_for_pg_lock(holder, pid, event)
        finally:
            holder.rollback()
        assert [future.result(timeout=5) for future in futures] == ["ok", "ok"]
    with session_factory() as check:
        assert set(check.scalars(select(RecipientDeliveryState.publication_sequence))) == {1, 2}
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 2


@pytest.mark.parametrize("grant_first", [True, False])
def test_lifetime_and_spend_share_one_access_lock(session_factory, tmp_path, grant_first):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, "v1", sender=sender)
        later = source(seed, tmp_path, "v2", sender=sender)
        seed.commit()

    def grant(session):
        return service.grant_lifetime(
            session, account_id=sender.user.id, grant_key="test-race", origin="test", now=NOW,
        )

    with session_factory() as holder, ThreadPoolExecutor(max_workers=1) as pool:
        grant(holder) if grant_first else publish(holder, item)
        pid, connected = [], Event()
        future = pool.submit(
            worker, session_factory, item, pid, connected,
            action=None if grant_first else grant,
        )
        try:
            wait_for_pg_lock(holder, pid, connected)
            holder.commit()
        finally:
            holder.rollback()
        assert future.result(timeout=5) == "ok"
    with session_factory() as check:
        assert check.get(SendPublication, item.capsule_id).funding == ("LIFETIME" if grant_first else "FREE")
        publish(check, later)
        check.commit()
        assert check.get(SendPublication, later.capsule_id).funding == "LIFETIME"


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_fresh_deadline_after_actual_access_lock_wait(session_factory, tmp_path, arm):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, arm, sender=sender)
        deadline = item.row(seed).draft_expires_at if arm == "v1" else item.row(seed).claim_expires_at
        seed.commit()
    clock = Clock(NOW)
    with session_factory() as holder, ThreadPoolExecutor(max_workers=1) as pool:
        holder.scalar(select(AccountSendAccess).where(
            AccountSendAccess.account_id == sender.user.id
        ).with_for_update())
        pid, connected = [], Event()
        future = pool.submit(worker, session_factory, item, pid, connected, clock=clock)
        try:
            wait_for_pg_lock(holder, pid, connected)
            clock.current = deadline
        finally:
            holder.rollback()
        assert future.result(timeout=5) == ("DRAFT_EXPIRED" if arm == "v1" else "GIFT_UNAVAILABLE")
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 0
        assert item.row(check).state == "DRAFT"


def test_concurrent_cross_arm_capsule_collision_rolls_back_loser(session_factory, tmp_path, monkeypatch):
    with session_factory() as seed:
        a, b = actor(seed), actor(seed)
        first = source(seed, tmp_path, "v1", sender=a)
        second = source(seed, tmp_path, "v2", sender=b, capsule_id=first.capsule_id)
        seed.commit()
    ready_to_record = Barrier(2)
    original = service.record

    def record_together(*args, **kwargs):
        ready_to_record.wait(timeout=3)
        return original(*args, **kwargs)

    monkeypatch.setattr(service, "record", record_together)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, session_factory, item, [], Event()) for item in (first, second)]
        results = [future.result(timeout=8) for future in futures]
        assert sorted(results) == ["SEND_ACCESS_UNAVAILABLE", "ok"]
    with session_factory() as check:
        assert check.scalar(select(func.count()).select_from(SendPublication)) == 1
        assert sum(item.row(check).state == "DRAFT" for item in (first, second)) == 1
