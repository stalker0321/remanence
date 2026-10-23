"""Upgrade only brand-new fixture databases, including a populated 0017 database."""
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from alembic import command
from fastapi.testclient import TestClient
from sqlalchemy import CheckConstraint, MetaData, Table, inspect, select, text

from test_me_endpoint import me_env
from test_passkey_recovery import seed, protect
from remanence.auth.google_models import GoogleIdentity
from remanence.db.session import build_engine, build_session_factory
from remanence.main import create_app
from remanence.registration_setup.models import RegistrationPolicy, RegistrationReservation, RegistrationCredential, RegistrationChallenge
from remanence.settings import Settings

MODELS = (RegistrationPolicy, RegistrationReservation, RegistrationCredential, RegistrationChallenge)


@pytest.fixture
def populated(monkeypatch, request):
    original = command.upgrade
    before = {}
    def upgrade(config, revision):
        assert revision == "head"
        original(config, "0017_send_access")
        cfg = Settings(); cfg.recovery_enabled = True
        engine = build_engine(cfg)
        factory = build_session_factory(engine)
        try:
            with TestClient(create_app(settings=cfg, session_factory=factory)) as client:
                actor = seed(client)
                protect(client, actor)
            user_id = uuid.UUID(actor["auth"].user)
            bundle_id = uuid.UUID(actor["bundle"])
            with factory.begin() as db:
                db.add(GoogleIdentity(subject="existing-before-0018", user_id=user_id))
            with engine.begin() as conn:
                now = datetime.now(timezone.utc)
                capsule = Table("capsules", MetaData(), autoload_with=conn)
                conn.execute(capsule.insert().values(id=uuid.uuid4(), sender_user_id=user_id,
                    recipient_user_id=user_id, sender_key_bundle_id=bundle_id, recipient_key_bundle_id=bundle_id,
                    protocol_version=1, state="READY", created_at=now, draft_expires_at=now + timedelta(days=1),
                    ready_at=now, signed_statement=b"migration-preserved", signed_statement_sha256=b"h"*32,
                    publish_signature=b"s"*69))
                pending = Table("pending_deliveries", MetaData(), autoload_with=conn)
                conn.execute(pending.insert().values(id=uuid.uuid4(), capsule_id=uuid.uuid4(), sender_user_id=user_id,
                    delivery_target_id=uuid.uuid4(), channel="email", state="PENDING", claim_expires_at=now + timedelta(days=1),
                    publish_statement=b"migration-pending", publish_statement_sha256=b"h"*32, publish_signature=b"s"*69,
                    address_commitment=b"a"*32, key_commitment=b"k"*32, vault_ciphertext=b"migration-opaque"))
                for name in inspect(conn).get_table_names():
                    if name == "alembic_version": continue
                    table = Table(name, MetaData(), autoload_with=conn)
                    before[name] = list(conn.execute(select(table)).mappings())
        finally:
            engine.dispose()
        original(config, "head")
    monkeypatch.setattr(command, "upgrade", upgrade)
    client, factory = request.getfixturevalue("me_env")
    return factory, before


def test_populated_additive_upgrade_preserves_every_existing_table(populated):
    factory, before = populated
    with factory() as db:
        for name, old in before.items():
            table = Table(name, MetaData(), autoload_with=db.connection())
            current = list(db.execute(select(table)).mappings())
            assert sorted(map(repr, current)) == sorted(map(repr, old)), name
        assert db.get(RegistrationPolicy, 1).required is False
        for model in MODELS[1:]: assert list(db.scalars(select(model))) == []


def test_empty_upgrade_matches_orm_and_no_permanent_foreign_key_weakening(me_env):
    with me_env[1]() as db:
        inspector = inspect(db.connection())
        assert db.scalar(text("SELECT version_num FROM alembic_version")) == "0019_pending_fk_names"
        for model in MODELS:
            table = model.__table__
            assert {x["name"] for x in inspector.get_columns(table.name)} == set(table.c.keys())
            assert {x["name"] for x in inspector.get_check_constraints(table.name)} == {
                x.name for x in table.constraints if isinstance(x, CheckConstraint)}
            assert {tuple(x["column_names"]) for x in inspector.get_unique_constraints(table.name)} == {
                tuple(column.name for column in x.columns) for x in table.constraints if type(x).__name__ == "UniqueConstraint"}
        assert {x["referred_table"] for x in inspector.get_foreign_keys("recovery_records")} == {"users", "user_key_bundles", "recovery_credentials"}
        assert db.get(RegistrationPolicy, 1).required is False


def test_atomic_flags_default_off_and_fail_closed_outside_test(me_env):
    cfg = me_env[0].app.state.settings
    assert not cfg.registration_atomic_required and not cfg.registration_atomic_admission_enabled
    baseline = cfg.model_dump()
    for changes in (
        {"registration_atomic_admission_enabled": True},
        {"registration_atomic_required": True},
        {"registration_atomic_required": True, "google_sign_in_enabled": True, "recovery_enabled": True, "mode": "prod"},
        {"registration_atomic_required": True, "google_sign_in_enabled": True, "recovery_enabled": True, "mode": "beta"},
    ):
        with pytest.raises(ValueError): Settings.model_validate({**baseline, **changes})
