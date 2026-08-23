"""Integration tests for the login endpoint against a temporary PostgreSQL database."""

import base64
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from psycopg import sql
from sqlalchemy import select
from sqlalchemy.engine import make_url
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from postmark.auth.models import AuthCredential, AuthSession
from postmark.auth.passwords import PasswordService
from postmark.auth.tokens import hash_opaque_token
from postmark.db.session import build_engine, build_session_factory
from postmark.main import create_app
from postmark.settings import AppMode, Settings
from postmark.users.key_models import KeyBundleStatus, UserKeyBundle
from postmark.users.models import User

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))
_PASSWORD = "correct horse battery staple"


def _hpke_public_key() -> hpke_pb2.HpkePublicKey:
    return hpke_pb2.HpkePublicKey(
        version=0,
        params=hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        ),
        public_key=_HPKE_KEY,
    )


def _ed25519_public_key() -> ed25519_pb2.Ed25519PublicKey:
    return ed25519_pb2.Ed25519PublicKey(version=0, key_value=_ED_KEY)


def _key_data(type_url: str, value: bytes) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(
        type_url=type_url,
        value=value,
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
    )


def _keyset(key_data: tink_pb2.KeyData) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=key_data,
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset


def _b64(data: bytes) -> str:
    return base64.b64encode(data, altchars=b"-_").decode("ascii").rstrip("=")


def _valid_registration_payload() -> dict:
    encryption = _keyset(_key_data("type.googleapis.com/google.crypto.tink.HpkePublicKey", _hpke_public_key().SerializeToString()))
    signing = _keyset(_key_data("type.googleapis.com/google.crypto.tink.Ed25519PublicKey", _ed25519_public_key().SerializeToString()))
    return {
        "email": "alice@example.com",
        "password": _PASSWORD,
        "handle": "alice",
        "key_bundle": {
            "key_bundle_id": "00010203-0405-0607-0809-0a0b0c0d0e0f",
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "encryption_public_keyset": _b64(encryption.SerializeToString()),
            "signing_public_keyset": _b64(signing.SerializeToString()),
        },
    }


@pytest.fixture()
def login_env(monkeypatch: pytest.MonkeyPatch):
    source = os.environ.get("POSTMARK_TEST_DATABASE_URL")
    if not source:
        pytest.skip("POSTMARK_TEST_DATABASE_URL is not set")
    url = make_url(source)
    database = f"postmark_tmp_{uuid4().hex}"
    admin: psycopg.Connection | None = None
    created = False
    try:
        admin = psycopg.connect(host=url.host, port=url.port, user=url.username, password=url.password, dbname="postgres", autocommit=True)
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        created = True
        for key in list(os.environ):
            if key.upper().startswith("POSTMARK_"):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.setenv("POSTMARK_MODE", "dev")
        monkeypatch.setenv("POSTMARK_DATABASE_URL", url.set(database=database).render_as_string(hide_password=False))
        monkeypatch.setenv("POSTMARK_BLOB_ROOT", "var/test-blobs")
        config = Config(str(_ALEMBIC_INI))
        config.set_main_option("path_separator", "os")
        command.upgrade(config, "head")
        settings = Settings()
        engine = build_engine(settings)
        factory = build_session_factory(engine)
        try:
            app = create_app(settings=settings, session_factory=factory)
            with TestClient(app) as client:
                yield client, factory
        finally:
            engine.dispose()
    finally:
        if admin is not None:
            try:
                if created:
                    admin.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = %s AND pid <> pg_backend_pid()", (database,))
                    admin.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(database)))
            finally:
                admin.close()


def _seed_register(client: TestClient) -> dict:
    response = client.post("/v1/auth/register", json=_valid_registration_payload())
    assert response.status_code == 201, response.text
    return response.json()


def test_login_success_creates_second_independent_root_lineage(login_env) -> None:
    client, factory = login_env
    _seed_register(client)
    response = client.post("/v1/auth/login", json={"email": "alice@example.com", "password": _PASSWORD})
    assert response.status_code == 200
    body = response.json()
    assert body["user"]["email"] == "alice@example.com"
    assert body["user"]["handle"] == "alice"
    assert body["access_token"].startswith("pm_at_")
    assert body["refresh_token"].startswith("pm_rt_")
    assert body["access_expires_at"].endswith("Z") or body["access_expires_at"].endswith("+00:00")
    assert body["refresh_expires_at"].endswith("Z") or body["refresh_expires_at"].endswith("+00:00")
    assert body["active_key_bundle"]["status"] == "ACTIVE"
    assert body["active_key_bundle"]["suite"] == "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
    assert body["active_key_bundle"]["protocol_version"] == 1

    with factory() as session:
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 2
        lineage_ids = {row.lineage_id for row in rows}
        assert len(lineage_ids) == 2
        login_row = next(row for row in rows if row.id == UUID(body["session_id"]))
        assert login_row.lineage_id == login_row.id
        assert login_row.parent_session_id is None
        assert login_row.access_token_hash == hash_opaque_token(body["access_token"])
        assert login_row.refresh_token_hash == hash_opaque_token(body["refresh_token"])


def test_login_email_whitespace_and_case_normalized(login_env) -> None:
    client, _ = login_env
    _seed_register(client)
    response = client.post("/v1/auth/login", json={"email": "  Alice@EXAMPLE.com  ", "password": _PASSWORD})
    assert response.status_code == 200
    assert response.json()["user"]["email"] == "alice@example.com"


def test_wrong_password_nonexistent_disabled_identical_401_no_session(login_env) -> None:
    client, factory = login_env
    _seed_register(client)
    wrong = client.post("/v1/auth/login", json={"email": "alice@example.com", "password": "wrong-password-here"})
    nonexistent = client.post("/v1/auth/login", json={"email": "nobody@example.com", "password": _PASSWORD})
    with factory() as session:
        user = session.scalars(select(User)).one()
        user.disabled_at = datetime(2030, 1, 1, tzinfo=timezone.utc)
        session.commit()
    disabled = client.post("/v1/auth/login", json={"email": "alice@example.com", "password": _PASSWORD})

    expected = {
        "type": "https://postmark.invalid/problems/invalid-credentials",
        "title": "Invalid credentials",
        "status": 401,
        "code": "INVALID_CREDENTIALS",
    }
    for response in (wrong, nonexistent, disabled):
        assert response.status_code == 401
        assert response.headers["content-type"].startswith("application/problem+json")
        assert response.json() == expected
        serialized = json.dumps(response.json())
        assert "alice@example.com" not in serialized
        assert "nobody@example.com" not in serialized
        assert "wrong-password-here" not in serialized
        assert _PASSWORD not in serialized
    with factory() as session:
        assert len(session.scalars(select(AuthSession)).all()) == 1


def test_legacy_argon_params_login_rehashes_and_stamps_now(login_env) -> None:
    client, factory = login_env
    _seed_register(client)
    from argon2 import PasswordHasher
    from argon2.low_level import Type

    legacy = PasswordHasher(
        time_cost=2,
        memory_cost=65536,
        parallelism=4,
        hash_len=32,
        salt_len=16,
        type=Type.ID,
    ).hash(_PASSWORD)
    with factory() as session:
        user = session.scalars(select(User)).one()
        credential = session.get(AuthCredential, user.id)
        credential.password_hash = legacy
        session.commit()
        old_changed_at = credential.password_changed_at

    response = client.post("/v1/auth/login", json={"email": "alice@example.com", "password": _PASSWORD})
    assert response.status_code == 200
    with factory() as session:
        user = session.scalars(select(User)).one()
        credential = session.get(AuthCredential, user.id)
        assert credential.password_hash != legacy
        assert credential.password_changed_at != old_changed_at


def test_response_repr_hides_tokens_and_password(login_env) -> None:
    client, _ = login_env
    _seed_register(client)
    from postmark.auth.login import LoginService

    with client.app.state.session_factory() as session:
        result = LoginService(session, PasswordService()).login(
            email_normalized="alice@example.com", password=_PASSWORD, now=datetime(2030, 1, 1, tzinfo=timezone.utc)
        )
        rendered = repr(result)
        assert result.access_token not in rendered
        assert result.refresh_token not in rendered
        assert _PASSWORD not in rendered


def test_malformed_login_input_422_redacted(login_env) -> None:
    client, _ = login_env
    _seed_register(client)
    response = client.post("/v1/auth/login", json={"email": "not-an-email", "password": "short"})
    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body == {
        "type": "https://postmark.invalid/problems/invalid-request",
        "title": "Invalid request",
        "status": 422,
        "code": "INVALID_REQUEST",
    }
    serialized = json.dumps(body)
    assert "not-an-email" not in serialized
    assert "short" not in serialized