"""Integration tests for the handle-change endpoint against a temporary PostgreSQL database."""

import base64
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

from remanence.auth.models import AuthCredential, AuthSession
from remanence.db.session import build_engine, build_session_factory
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.users.key_models import UserKeyBundle
from remanence.users.models import User

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))
_PASSWORD = "correct horse battery staple"


def _hpke_public_key() -> hpke_pb2.HpkePublicKey:
    return hpke_pb2.HpkePublicKey(version=0, params=hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256, kdf=hpke_pb2.HKDF_SHA256, aead=hpke_pb2.AES_256_GCM), public_key=_HPKE_KEY)


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


def _registration_payload(email="alice@example.com", handle="alice") -> dict:
    encryption = _keyset(_key_data("type.googleapis.com/google.crypto.tink.HpkePublicKey", _hpke_public_key().SerializeToString()))
    signing = _keyset(_key_data("type.googleapis.com/google.crypto.tink.Ed25519PublicKey", _ed25519_public_key().SerializeToString()))
    return {
        "email": email,
        "password": _PASSWORD,
        "handle": handle,
        "key_bundle": {
            "key_bundle_id": str(uuid4()),
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "encryption_public_keyset": _b64(encryption.SerializeToString()),
            "signing_public_keyset": _b64(signing.SerializeToString()),
        },
    }


@pytest.fixture()
def handle_env(monkeypatch: pytest.MonkeyPatch):
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


def _seed(client: TestClient, **kwargs) -> dict:
    response = client.post("/v1/auth/register", json=_registration_payload(**kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def test_change_handle_and_me_reflects(handle_env) -> None:
    client, factory = handle_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    response = client.patch("/v1/me/handle", json={"handle": "@New_Name"}, headers=headers)
    assert response.status_code == 200
    body = response.json()
    assert body["handle"] == "new_name"
    assert body["user_id"] == seed["user"]["user_id"]
    me = client.get("/v1/me", headers=headers).json()
    assert me["handle"] == "new_name"
    with factory() as session:
        user = session.scalars(select(User)).one()
        assert user.handle_normalized == "new_name"
        assert user.handle_display == "new_name"


def test_same_handle_repeated_200(handle_env) -> None:
    client, _ = handle_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    first = client.patch("/v1/me/handle", json={"handle": "new_name"}, headers=headers)
    assert first.status_code == 200
    second = client.patch("/v1/me/handle", json={"handle": "new_name"}, headers=headers)
    assert second.status_code == 200
    assert second.json()["handle"] == "new_name"


def test_duplicate_handle_fixed_409_original_unchanged(handle_env) -> None:
    client, factory = handle_env
    seed_a = _seed(client, email="alice@example.com", handle="alice")
    _seed(client, email="bob@example.com", handle="bob")
    response = client.patch("/v1/me/handle", json={"handle": "bob"}, headers={"Authorization": f"Bearer {seed_a['access_token']}"})
    assert response.status_code == 409
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body == {
        "type": "https://remanence.invalid/problems/handle-conflict",
        "title": "Handle conflict",
        "status": 409,
        "code": "HANDLE_CONFLICT",
    }
    assert "bob" not in str(body)
    with factory() as session:
        alice = next(u for u in session.scalars(select(User)).all() if u.handle_normalized == "alice")
        assert alice.handle_normalized == "alice"


def test_case_only_collision_409(handle_env) -> None:
    client, factory = handle_env
    seed_a = _seed(client, handle="alice")
    _seed(client, email="bob@example.com", handle="BOB")
    response = client.patch("/v1/me/handle", json={"handle": "Bob"}, headers={"Authorization": f"Bearer {seed_a['access_token']}"})
    assert response.status_code == 409
    assert response.json()["code"] == "HANDLE_CONFLICT"


def test_invalid_handles_422_redacted(handle_env) -> None:
    client, _ = handle_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    bad_handles = ["ab", "", "@@ab", "abc\u00e9", "ab c", "x" * 31]
    for handle in bad_handles:
        response = client.patch("/v1/me/handle", json={"handle": handle}, headers=headers)
        assert response.status_code == 422
        assert response.headers["content-type"].startswith("application/problem+json")
        body = response.json()
        assert body["code"] == "INVALID_REQUEST"
        if handle:
            assert handle not in str(body)
    extra = client.patch("/v1/me/handle", json={"handle": "new_name", "extra": 1}, headers=headers)
    assert extra.status_code == 422


def test_unauth_401(handle_env) -> None:
    client, _ = handle_env
    response = client.patch("/v1/me/handle", json={"handle": "new_name"})
    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


def test_immutable_ids_across_handle_change(handle_env) -> None:
    client, factory = handle_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    with factory() as session:
        before = {
            "user_id": session.scalars(select(User)).one().id,
            "cred_user_id": session.scalars(select(AuthCredential)).one().user_id,
            "bundle_user_id": session.scalars(select(UserKeyBundle)).one().user_id,
            "sessions": [(row.id, row.user_id, row.lineage_id) for row in session.scalars(select(AuthSession)).all()],
        }
    response = client.patch("/v1/me/handle", json={"handle": "changed"}, headers=headers)
    assert response.status_code == 200
    with factory() as session:
        assert session.scalars(select(User)).one().id == before["user_id"]
        assert session.scalars(select(AuthCredential)).one().user_id == before["cred_user_id"]
        assert session.scalars(select(UserKeyBundle)).one().user_id == before["bundle_user_id"]
        after_sessions = [(row.id, row.user_id, row.lineage_id) for row in session.scalars(select(AuthSession)).all()]
        assert after_sessions == before["sessions"]