"""Additive migration/SQL evidence using only the existing random-DB fixture."""

import uuid
from datetime import timedelta

import pytest
from alembic import command
from sqlalchemy import CheckConstraint, MetaData, Table, inspect, select, text, update
from sqlalchemy.exc import IntegrityError
from tink import tink_config

from remanence.capsules.models import Capsule
from remanence.db.session import build_engine
from remanence.pending.claim_models import PendingDelivery
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.service import grant_lifetime, provision
from remanence.settings import Settings
from send_access_support import NOW, actor, publish, source

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


@pytest.fixture()
def populated_upgrade(monkeypatch, request):
    original = command.upgrade
    snapshots = {}

    def staged_upgrade(config, revision):
        assert revision == "head"
        original(config, "0016_finalize_binding")
        engine = build_engine(Settings())  # The existing fixture's unique DB, not its admin URL.
        try:
            with engine.begin() as connection:
                metadata = MetaData()
                tables = {name: Table(name, metadata, autoload_with=connection) for name in (
                    "users", "user_key_bundles", "capsules", "pending_deliveries",
                )}
                users = [uuid.uuid4() for _ in range(3)]
                for index, user_id in enumerate(users):
                    handle = None if index == 1 else f"old{index}"
                    connection.execute(tables["users"].insert().values(
                        id=user_id, email_normalized=f"old{index}@example.test",
                        handle_normalized=handle, handle_display=handle,
                        disabled_at=NOW if index == 2 else None,
                    ))
                bundle = uuid.uuid4()
                connection.execute(tables["user_key_bundles"].insert().values(
                    id=bundle, user_id=users[0], encryption_public_keyset=b"synthetic-old",
                    signing_public_keyset=b"synthetic-old", suite="synthetic-migration-only",
                    protocol_version=1, status="ACTIVE",
                ))
                for published in (False, True):
                    connection.execute(tables["capsules"].insert().values(
                        id=uuid.uuid4(), sender_user_id=users[0], recipient_user_id=users[0],
                        sender_key_bundle_id=bundle, recipient_key_bundle_id=bundle,
                        protocol_version=1, state="READY" if published else "DRAFT",
                        created_at=NOW - timedelta(days=1), draft_expires_at=NOW + timedelta(days=1),
                        ready_at=NOW if published else None,
                        signed_statement=b"old-bytes" if published else None,
                        signed_statement_sha256=b"h" * 32 if published else None,
                        publish_signature=b"s" * 69 if published else None,
                    ))
                    connection.execute(tables["pending_deliveries"].insert().values(
                        id=uuid.uuid4(), capsule_id=uuid.uuid4(), sender_user_id=users[0],
                        delivery_target_id=uuid.uuid4(), channel="email",
                        state="PENDING" if published else "DRAFT", claim_expires_at=NOW + timedelta(days=1),
                        publish_statement=b"old-pending" if published else None,
                        publish_statement_sha256=b"h" * 32 if published else None,
                        publish_signature=b"s" * 69 if published else None,
                        address_commitment=b"a" * 32 if published else None,
                        key_commitment=b"k" * 32 if published else None,
                        vault_ciphertext=b"old-sealed" if published else None,
                    ))
                for name, table in tables.items():
                    snapshots[name] = [dict(row) for row in connection.execute(select(table).order_by(table.c.id)).mappings()]
        finally:
            engine.dispose()
        original(config, "head")

    monkeypatch.setattr(command, "upgrade", staged_upgrade)
    factory = request.getfixturevalue("session_factory")
    return factory, snapshots


def test_populated_upgrade_preserves_all_old_source_bytes_and_nulls(populated_upgrade):
    factory, snapshots = populated_upgrade
    with factory() as session:
        for name, before in snapshots.items():
            table = Table(name, MetaData(), autoload_with=session.connection())
            columns = [table.c[name] for name in before[0]]
            after = [dict(row) for row in session.execute(select(*columns).order_by(table.c.id)).mappings()]
            assert after == before
        access = list(session.scalars(select(AccountSendAccess)))
        assert {row.account_id for row in access} == {row["id"] for row in snapshots["users"]}
        assert all(row.policy_version == 1 and row.lifetime_granted_at is None for row in access)
        assert list(session.scalars(select(SendPublication))) == []
        assert list(session.scalars(select(Capsule.send_accounting_version))) == [None, None]
        assert list(session.scalars(select(PendingDelivery.send_accounting_version))) == [None, None]
        assert list(session.scalars(select(PendingDelivery.finalize_operation_id))) == [None, None]


def test_empty_upgrade_named_constraints_and_orm_match(session_factory):
    with session_factory() as session:
        inspector = inspect(session.connection())
        assert session.scalar(text("SELECT version_num FROM alembic_version")) == "0018_atomic_registration"
        for model in (AccountSendAccess, SendPublication):
            table = model.__table__
            assert {column["name"] for column in inspector.get_columns(table.name)} == set(table.c.keys())
            assert inspector.get_pk_constraint(table.name)["name"] == table.primary_key.name
            actual_checks = {item["name"] for item in inspector.get_check_constraints(table.name)}
            assert actual_checks == {item.name for item in table.constraints if isinstance(item, CheckConstraint)}
            actual_unique = {item["name"]: tuple(item["column_names"]) for item in inspector.get_unique_constraints(table.name)}
            expected_unique = {item.name: tuple(column.name for column in item.columns)
                               for item in table.constraints if type(item).__name__ == "UniqueConstraint"}
            assert actual_unique == expected_unique
            actual_fks = {item["name"]: item["options"].get("ondelete") for item in inspector.get_foreign_keys(table.name)}
            assert actual_fks == {item.name: item.ondelete for item in table.foreign_key_constraints}
        assert not SendPublication.__table__.c.recipient_user_id_at_publish.foreign_keys
        for name in ("capsules", "pending_deliveries"):
            marker = next(column for column in inspector.get_columns(name) if column["name"] == "send_accounting_version")
            assert marker["nullable"] and marker["default"] is None
            assert f"ck_{name}_send_accounting" in {item["name"] for item in inspector.get_check_constraints(name)}
        assert list(session.scalars(select(AccountSendAccess))) == []
        assert list(session.scalars(select(SendPublication))) == []


def test_sql_receipt_shapes_slots_source_restrict_and_marker_constraints(session_factory, tmp_path):
    with session_factory() as session:
        sender = actor(session)
        items = [source(session, tmp_path, arm, sender=sender) for arm in ("v1", "v2", "v1")]
        session.commit()
        for item in items[:2]:
            publish(session, item)
            session.commit()
        bad_values = [
            ({"free_slot": None}, "funding"), ({"free_slot": 3}, "funding"),
            ({"funding": "LIFETIME"}, "funding"), ({"funding": "GRANT"}, "funding"),
            ({"policy_version": 2}, "policy"), ({"publication_sha256": b"short"}, "hash"),
            ({"recipient_user_id_at_publish": None}, "classification"),
            ({"classification": "ACCOUNT_EQUAL"}, "classification"),
            ({"bucket": "SELF"}, "classification"),
            ({"v1_capsule_id": None}, "classification"),
            ({"v1_capsule_id": items[2].id}, "source"),
        ]
        for values, suffix in bad_values:
            with pytest.raises(IntegrityError) as error:
                with session.begin_nested():
                    session.execute(update(SendPublication).where(
                        SendPublication.capsule_id == items[0].capsule_id
                    ).values(**values))
            assert error.value.orig.diag.constraint_name == f"ck_send_publications_{suffix}"
        with pytest.raises(IntegrityError) as error:
            with session.begin_nested():
                session.execute(update(SendPublication).where(
                    SendPublication.capsule_id == items[1].capsule_id
                ).values(free_slot=1))
        assert error.value.orig.diag.constraint_name == "uq_send_publications_free_slot"
        for item in items[:2]:
            with pytest.raises(IntegrityError):
                with session.begin_nested():
                    session.delete(item.row(session))
                    session.flush()
        with pytest.raises(IntegrityError) as error:
            with session.begin_nested():
                items[2].row(session).send_accounting_version = 1
                session.flush()
        assert error.value.orig.diag.constraint_name == "ck_capsules_send_accounting"
        pending = source(session, tmp_path, "v2", sender=sender)
        with pytest.raises(IntegrityError) as error:
            with session.begin_nested():
                pending.row(session).state = "CANCELLED"
                pending.row(session).send_accounting_version = 1
                session.flush()
        assert error.value.orig.diag.constraint_name == "ck_pending_deliveries_send_accounting"


def test_sql_lifetime_trio_and_provision_never_resets(session_factory, tmp_path):
    with session_factory() as session:
        sender = actor(session)
        item = source(session, tmp_path, "v1", sender=sender)
        session.commit()
        publish(session, item)
        grant_lifetime(session, account_id=sender.user.id, grant_key="synthetic-grant", origin="test", now=NOW)
        session.commit()
        for values in ({"lifetime_grant_key": None}, {"lifetime_grant_origin": " "}, {"policy_version": 2}):
            with pytest.raises(IntegrityError):
                with session.begin_nested():
                    session.execute(update(AccountSendAccess).where(
                        AccountSendAccess.account_id == sender.user.id
                    ).values(**values))
        provision(session, sender.user.id, now=NOW + timedelta(days=7))
        session.commit()
        session.expire_all()
        row = session.get(AccountSendAccess, sender.user.id)
        assert row.created_at == NOW and row.lifetime_granted_at == NOW
        assert row.lifetime_grant_key == "synthetic-grant"
        assert session.get(SendPublication, item.capsule_id).free_slot == 1
