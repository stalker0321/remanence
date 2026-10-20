"""Accepted owner read contract through the real app, auth and synthetic PG."""

from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, event, text, update
from sqlalchemy.exc import SQLAlchemyError
from tink import tink_config

from remanence.api.dependencies import get_db_session
from remanence.api.problems import PROBLEM_BODY_FIELDS
from remanence.auth.models import AuthSession
from remanence.main import create_app
from remanence.pending.publication import cancel_delivery
from remanence.send_access import api
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode as Mode
from remanence.send_access.service import grant_lifetime
from remanence.settings import AppMode, Settings
from remanence.users.models import User
from send_access_http_support import bearer
from send_access_support import NOW, actor, publish, source

pytest_plugins = ("test_session_repository_create",)
PATH = "/v1/me/send-access"


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


def client_for(factory, mode=Mode.ENFORCE):
    return TestClient(create_app(Settings(mode=AppMode.TEST, send_access_mode=mode), session_factory=factory),
                      raise_server_exceptions=False)


def private(response, status=200, code=None):
    assert response.status_code == status, response.text
    assert response.headers["cache-control"] == "private, no-store"
    assert "etag" not in response.headers
    data = response.json()
    if code is not None:
        assert set(data) == PROBLEM_BODY_FIELDS
        assert data["code"] == code
        assert data["retryable"] == (status == 503)
    return data


def exact(data, *, mode="enforce", lifetime=False, own=0, other=0):
    assert data == {
        "policy_version": 1, "mode": mode, "lifetime": lifetime,
        "free": {"self": {"limit": 2, "used": own, "remaining": 2 - own},
                 "other": {"limit": 2, "used": other, "remaining": 2 - other}},
        "can_publish": {"self": mode == "record" or lifetime or own < 2,
                        "other": mode == "record" or lifetime or other < 2},
    }
    assert type(data["lifetime"]) is bool
    assert all(type(value) is bool for value in data["can_publish"].values())


def test_off_is_authenticated_404_without_accounting_query(session_factory):
    with session_factory() as seed:
        sender = actor(seed, provisioned=False)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
    engine = session_factory.kw["bind"]
    statements = []
    def observe(conn, cursor, sql, parameters, context, many):
        statements.append(sql.lower())
    event.listen(engine, "before_cursor_execute", observe)
    try:
        # Omit the setting entirely, proving the app's default OFF.
        with TestClient(create_app(Settings(mode=AppMode.TEST), session_factory=session_factory)) as client:
            private(client.get(PATH), 401, "AUTH_INVALID")
            private(client.get(PATH, headers=headers), 404, "ROUTE_NOT_FOUND")
    finally:
        event.remove(engine, "before_cursor_execute", observe)
    assert statements and all(sql.lstrip().startswith("select") for sql in statements)
    assert not any("account_send_access" in sql or "send_publications" in sql for sql in statements)


def test_owner_counts_modes_lifetime_and_cancel_never_reset(session_factory, tmp_path):
    with session_factory() as seed:
        sender, foreign = actor(seed), actor(seed)
        headers, _ = bearer(seed, sender.user.id)
        foreign_headers, _ = bearer(seed, foreign.user.id)
        items = [source(seed, tmp_path, "v1", sender=sender, recipient=sender) for _ in range(2)]
        pending = source(seed, tmp_path, "v2", sender=sender)
        exempt = source(seed, tmp_path, "v1", sender=sender)
        unlimited = source(seed, tmp_path, "v1", sender=sender)
        seed.commit()
    with client_for(session_factory) as client:
        exact(private(client.get(PATH, headers=headers)))
        for item in items + [pending]:
            with session_factory() as writer:
                publish(writer, item)
                writer.commit()
        exact(private(client.get(PATH, headers=headers)), own=2, other=1)
        exact(private(client.get(PATH, headers=foreign_headers)))
        with session_factory() as writer:
            cancel_delivery(writer, delivery_id=pending.id, sender_user_id=sender.user.id)
            publish(writer, exempt, mode=Mode.RECORD)
            writer.commit()
        client.app.state.settings = client.app.state.settings.model_copy(update={"send_access_mode": Mode.RECORD})
        exact(private(client.get(PATH, headers=headers)), mode="record", own=2, other=1)
        with session_factory() as writer:
            grant_lifetime(writer, account_id=sender.user.id, grant_key="private-never-on-wire", origin="synthetic", now=NOW)
            publish(writer, unlimited)
            writer.commit()
        client.app.state.settings = client.app.state.settings.model_copy(update={"send_access_mode": Mode.ENFORCE})
        result = private(client.get(PATH, headers=headers))
        exact(result, lifetime=True, own=2, other=1)
        assert "private-never-on-wire" not in str(result)
        client.app.state.settings = client.app.state.settings.model_copy(update={"send_access_mode": Mode.OFF})
        private(client.get(PATH, headers=headers), 404, "ROUTE_NOT_FOUND")


@pytest.mark.parametrize("fault", ["absent", "malformed", "unknown", "expired", "revoked", "disabled"])
def test_real_auth_failures_are_private(session_factory, fault):
    with session_factory() as seed:
        sender = actor(seed)
        headers, session_id = bearer(seed, sender.user.id)
        if fault in ("expired", "revoked"):
            row = seed.get(AuthSession, session_id)
            if fault == "expired":
                row.access_expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
            else:
                row.revoked_at = NOW
        if fault == "disabled":
            sender.user.disabled_at = NOW
        seed.commit()
    if fault == "absent":
        headers = {}
    elif fault == "malformed":
        headers = {"Authorization": "Bearer bad"}
    elif fault == "unknown":
        headers = {"Authorization": "Bearer pm_at_" + "A" * 43}
    with client_for(session_factory) as client:
        response = client.get(PATH, headers=headers)
        private(response, 401, "AUTH_INVALID")
        assert response.headers["www-authenticate"] == "Bearer"


@pytest.mark.parametrize("suffix,body", [("?owner_id=foreign", None), ("?mode=off", None), ("?lifetime=true", None), ("", b"{}")])
def test_no_client_selector_or_body(session_factory, suffix, body):
    with session_factory() as seed:
        sender = actor(seed)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
    with client_for(session_factory) as client:
        private(client.request("GET", PATH + suffix, headers=headers, content=body), 422, "VALIDATION_FAILED")
        exact(private(client.get(PATH, headers={**headers, "X-Owner-Id": "foreign", "X-Send-Access-Mode": "off"})))
        redirect = client.get(PATH + "/", headers=headers, follow_redirects=False)
        assert redirect.status_code == 307 and redirect.headers["cache-control"] == "private, no-store"
        assert "etag" not in redirect.headers
        exact(private(client.get(PATH + "/", headers=headers)))


@pytest.mark.parametrize("fault", ["missing", "policy", "lifetime", "lifetime_origin", "count", "schema"])
def test_active_missing_corrupt_or_absent_schema_is_unavailable(session_factory, tmp_path, fault):
    with session_factory() as seed:
        sender = actor(seed)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
        # Deliberate corruption ONLY in this fixture's random, disposable DB.
        assert seed.scalar(text("SELECT current_database()" )).startswith("remanence_tmp_")
        if fault == "missing":
            seed.execute(delete(AccountSendAccess).where(AccountSendAccess.account_id == sender.user.id))
        elif fault in ("policy", "lifetime", "lifetime_origin"):
            name = "ck_account_send_access_policy" if fault == "policy" else "ck_account_send_access_lifetime_shape"
            seed.execute(text(f"ALTER TABLE account_send_access DROP CONSTRAINT {name}"))
            values = {"policy_version": 2} if fault == "policy" else {"lifetime_grant_key": "orphaned"}
            if fault == "lifetime_origin":
                values["lifetime_granted_at"] = NOW
            seed.execute(update(AccountSendAccess).where(AccountSendAccess.account_id == sender.user.id).values(**values))
        elif fault == "schema":
            seed.execute(text("ALTER TABLE account_send_access RENAME COLUMN policy_version TO unavailable_policy"))
        else:
            for _ in range(3):
                item = source(seed, tmp_path, "v1", sender=sender)
                seed.commit()
                publish(seed, item, mode=Mode.RECORD)
                seed.commit()
            seed.execute(text("ALTER TABLE send_publications DROP CONSTRAINT ck_send_publications_funding"))
            seed.execute(update(SendPublication).values(funding="FREE"))
        seed.commit()
    with client_for(session_factory) as client:
        for mode in (Mode.RECORD, Mode.ENFORCE):
            client.app.state.settings = client.app.state.settings.model_copy(update={"send_access_mode": mode})
            result = private(client.get(PATH, headers=headers), 503, "SEND_ACCESS_UNAVAILABLE")
            assert "free" not in result and "orphaned" not in str(result)


@pytest.mark.parametrize("fault", ["factory", "auth_sql", "metadata_sql", "dependency", "unexpected_query"])
def test_dependency_database_and_unexpected_errors_are_bounded_private(session_factory, monkeypatch, fault):
    with session_factory() as seed:
        sender = actor(seed)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
    with client_for(session_factory) as client:
        engine = session_factory.kw["bind"]
        hits = []
        def fail_sql(conn, cursor, sql, params, ctx, many):
            target = "auth_sessions" if fault == "auth_sql" else "account_send_access"
            if target in sql:
                hits.append(1)
                raise SQLAlchemyError("synthetic detail must not escape")
        if fault == "factory":
            client.app.state.session_factory = None
        elif fault == "dependency":
            def failed_dependency():
                raise RuntimeError("synthetic detail must not escape")
            client.app.dependency_overrides[get_db_session] = failed_dependency
        elif fault == "unexpected_query":
            def failed_query(*args, **kwargs):
                raise RuntimeError("synthetic detail must not escape")
            monkeypatch.setattr(api, "read_access", failed_query)
        else:
            event.listen(engine, "before_cursor_execute", fail_sql)
        try:
            status = 500 if fault in ("dependency", "unexpected_query") else 503
            response = client.get(PATH, headers=headers)
            private(response, status, "INTERNAL_ERROR" if status == 500 else "SEND_ACCESS_UNAVAILABLE")
            assert "synthetic detail" not in response.text
            if fault in ("auth_sql", "metadata_sql"):
                assert hits == [1]
        finally:
            if fault in ("auth_sql", "metadata_sql"):
                event.remove(engine, "before_cursor_execute", fail_sql)
