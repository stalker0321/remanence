"""Integration tests for the logout endpoint against a temporary PostgreSQL database."""

import base64
import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from psycopg import sql
from sqlalchemy import select
from sqlalchemy.engine import make_url
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from remanence.auth.models import AuthSession
from remanence.auth.tokens import hash_opaque_token
from remanence.db.session import build_engine, build_session_factory
from remanence.main import create_app
from remanence.settings import AppMode, Settings

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
    return tink_pb2.KeyData(type_url=type_url, value=value, key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC)


def _keyset(key_data: tink_pb2.KeyData) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(tink_pb2.Keyset.Key(key_data=key_data, status=tink_pb2.ENABLED, key_id=1, output_prefix_type=tink_pb2.TINK))
    return keyset


def _b64(data: bytes) -> str:
    return base64.b64encode(data, altchars=b"-_").decode("ascii").rstrip("=")


def _registration_payload() -> dict:
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
def logout_env(monkeypatch: pytest.MonkeyPatch):
    source = os.environ.get("REMANENCE_TEST_DATABASE_URL")
    if not source:
        pytest.skip("REMANENCE_TEST_DATABASE_URL is not set")
    url = make_url(source)
    database = f"remanence_tmp_{uuid4().hex}"
    admin: psycopg.Connection | None = None
    created = False
    try:
        admin = psycopg.connect(host=url.host, port=url.port, user=url.username, password=url.password, dbname="postgres", autocommit=True)
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        created = True
        for key in list(os.environ):
            if key.upper().startswith("REMANENCE_"):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.setenv("REMANENCE_MODE", "dev")
        monkeypatch.setenv("REMANENCE_DATABASE_URL", url.set(database=database).render_as_string(hide_password=False))
        monkeypatch.setenv("REMANENCE_BLOB_ROOT", "var/test-blobs")
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


def _seed(client: TestClient) -> dict:
    response = client.post("/v1/auth/register", json=_registration_payload())
    assert response.status_code == 201, response.text
    return response.json()


def _logout(client: TestClient, access_token: str):
    return client.post("/v1/auth/logout", headers={"Authorization": f"Bearer {access_token}"})


def test_logout_revokes_root_and_protected_use_becomes_401(logout_env) -> None:
    client, factory = logout_env
    seed = _seed(client)
    response = _logout(client, seed["access_token"])
    assert response.status_code == 204
    assert response.content == b""
    with factory() as session:
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 1
        assert rows[0].revoked_at is not None


def test_repeated_logout_idempotent_same_revoked_at(logout_env) -> None:
    client, factory = logout_env
    seed = _seed(client)
    first = _logout(client, seed["access_token"])
    assert first.status_code == 204
    with factory() as session:
        revoked = session.scalars(select(AuthSession)).one().revoked_at
    second = _logout(client, seed["access_token"])
    assert second.status_code == 204
    with factory() as session:
        assert session.scalars(select(AuthSession)).one().revoked_at == revoked


def test_logout_with_child_access_after_refresh_revokes_root_and_child(logout_env) -> None:
    client, factory = logout_env
    seed = _seed(client)
    refresh = client.post("/v1/auth/refresh", json={"refresh_token": seed["refresh_token"]})
    assert refresh.status_code == 200
    child_access = refresh.json()["access_token"]
    response = _logout(client, child_access)
    assert response.status_code == 204
    with factory() as session:
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 2
        assert all(row.revoked_at is not None for row in rows)


def test_logout_with_old_rotated_access_revokes_lineage(logout_env) -> None:
    client, factory = logout_env
    seed = _seed(client)
    refresh = client.post("/v1/auth/refresh", json={"refresh_token": seed["refresh_token"]})
    assert refresh.status_code == 200
    response = _logout(client, seed["access_token"])
    assert response.status_code == 204
    with factory() as session:
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 2
        assert all(row.revoked_at is not None for row in rows)


def test_unknown_valid_token_204_no_mutation(logout_env) -> None:
    client, factory = logout_env
    _seed(client)
    response = _logout(client, "pm_at_" + "A" * 43)
    assert response.status_code == 204
    assert response.content == b""
    with factory() as session:
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 1
        assert rows[0].revoked_at is None


def test_missing_malformed_authorization_401(logout_env) -> None:
    client, _ = logout_env
    missing = client.post("/v1/auth/logout")
    assert missing.status_code == 401
    assert missing.headers["content-type"].startswith("application/problem+json")
    assert missing.json()["code"] == "AUTHENTICATION_REQUIRED"
    malformed = client.post("/v1/auth/logout", headers={"Authorization": "Basic abc"})
    assert malformed.status_code == 401
    assert malformed.json()["code"] == "AUTHENTICATION_REQUIRED"