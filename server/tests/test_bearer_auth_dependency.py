"""Integration tests for the bearer authentication dependency against a temporary PostgreSQL."""

import base64
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from fastapi import Depends
from fastapi.testclient import TestClient
from psycopg import sql
from sqlalchemy import select
from sqlalchemy.engine import make_url
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from remanence.api.auth_schemas import LoginRequest, LoginResponse, RegistrationRequest, RegistrationResponse
from remanence.api.problems import problem_payload
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
)
from remanence.auth.models import AuthSession
from remanence.db.session import build_engine, build_session_factory
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.users.models import User

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
def auth_env(monkeypatch: pytest.MonkeyPatch):
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

            from fastapi import APIRouter

            test_router = APIRouter()

            @test_router.get("/test/protected")
            def protected(principal: AuthenticatedPrincipal = Depends(get_authenticated_principal)) -> dict:
                return {"user_id": str(principal.user_id), "session_id": str(principal.session_id)}

            app.include_router(test_router)
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


def _assert_401(response) -> None:
    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == problem_payload("AUTH_INVALID", response.headers["x-request-id"])


def test_valid_access_returns_opaque_ids(auth_env) -> None:
    client, factory = auth_env
    seed = _seed(client)
    with factory() as session:
        auth_session = session.scalars(select(AuthSession)).one()
        session_id = str(auth_session.id)
    response = client.get("/test/protected", headers={"Authorization": f"Bearer {seed['access_token']}"})
    assert response.status_code == 200
    body = response.json()
    assert body["user_id"] == seed["user"]["user_id"]
    assert body["session_id"] == session_id


def test_missing_and_malformed_identical_401(auth_env) -> None:
    client, factory = auth_env
    _seed(client)
    non_ascii = b"Bearer pm_at_" + "\u00e9".encode("latin-1") * 4
    cases = {
        "missing": {},
        "lowercase scheme": {"Authorization": f"bearer {'A' * 43}"},
        "refresh token": {"Authorization": "Bearer " + "pm_rt_" + "B" * 43},
        "non-ascii": {"Authorization": non_ascii},
        "unknown": {"Authorization": "Bearer " + "pm_at_" + "C" * 43},
        "malformed payload": {"Authorization": "Bearer pm_at_short"},
    }
    shapes = []
    for label, headers in cases.items():
        response = client.get("/test/protected", headers=headers)
        _assert_401(response)
        body = response.json()
        shapes.append({key: value for key, value in body.items() if key != "request_id"})
        assert response.headers["www-authenticate"] == "Bearer"
    assert all(shape == shapes[0] for shape in shapes[1:])


def test_rotated_old_access_invalid_after_refresh(auth_env) -> None:
    client, factory = auth_env
    seed = _seed(client)
    refresh_response = client.post("/v1/auth/refresh", json={"refresh_token": seed["refresh_token"]})
    assert refresh_response.status_code == 200
    old_access = seed["access_token"]
    response = client.get("/test/protected", headers={"Authorization": f"Bearer {old_access}"})
    _assert_401(response)


def test_dependency_does_not_mutate_session_fields(auth_env) -> None:
    client, factory = auth_env
    seed = _seed(client)
    with factory() as session:
        before = session.scalars(select(AuthSession)).one()
        session.expunge_all()
    response = client.get("/test/protected", headers={"Authorization": f"Bearer {seed['access_token']}"})
    assert response.status_code == 200
    with factory() as session:
        after = session.scalars(select(AuthSession)).one()
        assert after.last_used_at == before.last_used_at
        assert after.rotated_at == before.rotated_at
        assert after.revoked_at == before.revoked_at


def test_test_app_no_db_auth_returns_503(auth_env, monkeypatch) -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    client = TestClient(app)
    response = client.post("/v1/auth/register", json=_registration_payload())
    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")