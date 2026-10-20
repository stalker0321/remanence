"""Real READ COMMITTED snapshots, no locks/writes/autoflush, advisory only."""

from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlalchemy import event, func, select, text
from tink import tink_config

from remanence.api.dependencies import get_db_session
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode as Mode
from remanence.send_access.query import read_access
from remanence.send_access.service import grant_lifetime
from send_access_http_support import bearer
from send_access_support import NOW, actor, publish, source
from test_send_access_publication import ERRORS
from test_send_access_read import PATH, client_for, exact, private

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def test_actual_http_single_select_ignores_dirty_identity_map_without_autoflush(session_factory):
    with session_factory() as seed:
        sender = actor(seed)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
    with session_factory() as dirty, client_for(session_factory) as client:
        dirty.autoflush = True
        row = dirty.get(AccountSendAccess, sender.user.id)
        row.policy_version = 2  # Would fail the DB constraint if autoflushed.
        sql, calls = [], []
        def no_flush(*args):
            pytest.fail("read flushed a caller-owned dirty session")
        event.listen(dirty, "before_flush", no_flush)
        connection = dirty.connection()
        def observe(conn, cursor, statement, parameters, context, many):
            sql.append(statement.lower())
        event.listen(connection, "before_cursor_execute", observe)

        def sessions():
            calls.append(1)
            if len(calls) == 1:  # Real auth has its own clean session, just like the default wiring.
                with session_factory() as authentication:
                    yield authentication
            else:
                yield dirty

        client.app.dependency_overrides[get_db_session] = sessions
        exact(private(client.get(PATH, headers=headers)))
        assert len(calls) == 2
        assert len(sql) == 1 and sql[0].startswith("select ") and "for update" not in sql[0]
        assert sql[0].count("count(*)") == 2 and "account_send_access" in sql[0]
        assert row.policy_version == 2 and dirty.is_modified(row)  # GET neither repaired nor expired it.
        dirty.rollback()
    with session_factory() as observer:
        assert observer.get(AccountSendAccess, sender.user.id).policy_version == 1
        assert observer.scalar(select(func.count()).select_from(SendPublication)) == 0


def test_scalar_snapshot_ignores_stale_previously_loaded_lifetime(session_factory):
    with session_factory() as seed:
        sender = actor(seed)
        seed.commit()
    with session_factory() as reader:
        cached = reader.get(AccountSendAccess, sender.user.id)
        assert cached.lifetime_granted_at is None
        with session_factory() as writer:
            grant_lifetime(writer, account_id=sender.user.id, grant_key="stale-map", origin="test", now=NOW)
            writer.commit()
        exact(read_access(reader, account_id=sender.user.id, mode=Mode.ENFORCE), lifetime=True)
        assert cached.lifetime_granted_at is None  # Proof this was NOT an identity-map read/repair.


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_one_statement_snapshot_during_real_publication_and_grant_commit(session_factory, tmp_path, arm):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, arm, sender=sender)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
    engine = session_factory.kw["bind"]
    sql, snapshot_read, committed = [], Event(), Event()

    def after_select(conn, cursor, statement, parameters, context, many):
        if statement.lstrip().lower().startswith("select") and "account_send_access" in statement:
            sql.append(statement.lower())
            snapshot_read.set()
            assert committed.wait(3), "reader blocked commit or did not reach the single SELECT"

    with client_for(session_factory) as client, session_factory() as writer, ThreadPoolExecutor(max_workers=1) as pool:
        assert writer.scalar(text("SHOW transaction_isolation")) == "read committed"
        publish(writer, item)
        grant_lifetime(writer, account_id=sender.user.id, grant_key="snapshot-commit", origin="test", now=NOW)
        # Writer holds publication/access locks and uncommitted receipt + lifetime.
        event.listen(engine, "after_cursor_execute", after_select)
        future = pool.submit(client.get, PATH, headers=headers)
        try:
            assert snapshot_read.wait(3), "readonly GET waited for the writer's account lock"
            writer.commit()  # Both changes become visible together, while reader still holds its result.
            client.app.state.settings = client.app.state.settings.model_copy(update={"send_access_mode": Mode.RECORD})
        finally:
            committed.set()
        response = future.result(timeout=5)
        event.remove(engine, "after_cursor_execute", after_select)
        exact(private(response))  # Old mode captured once; old snapshot: neither change, not a mixed pair.
        assert len(sql) == 1 and "for update" not in sql[0]
        exact(private(client.get(PATH, headers=headers)), mode="record", lifetime=True, other=1)


def test_read_is_advisory_not_slot_reservation(session_factory, tmp_path):
    with session_factory() as seed:
        sender = actor(seed)
        items = [source(seed, tmp_path, "v1", sender=sender) for _ in range(3)]
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
        publish(seed, items[0])
        seed.commit()
    with client_for(session_factory) as client:
        exact(private(client.get(PATH, headers=headers)), other=1)
        exact(private(client.get(PATH, headers=headers)), other=1)
        with session_factory() as writer:
            publish(writer, items[1])
            writer.commit()
        with session_factory() as loser:
            with pytest.raises(ERRORS) as error:
                publish(loser, items[2])
            assert error.value.code == "SEND_LIMIT_REACHED"
            loser.rollback()
            assert items[2].row(loser).state == "DRAFT"
        exact(private(client.get(PATH, headers=headers)), other=2)
