"""Full application ingestion/privacy contract, using synthetic data only."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import importlib.util
import json
import logging
import multiprocessing
from pathlib import Path
import sys

from alembic.migration import MigrationContext
from alembic.operations import Operations
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, select, text
from sqlalchemy.orm import sessionmaker

from remanence.main import create_app
from remanence.settings import Settings
from remanence.telemetry.ingress import (
    GlobalIntakeLimit, PATH, TelemetryAccessFilter, TelemetryErrorFilter, TelemetryIngress,
)
from remanence.telemetry.models import AnonymousTelemetryReport
from remanence.telemetry.schemas import STYLES
from remanence.telemetry.store import StoreFull, StoreUnavailable, TelemetryStore
from test_me_endpoint import me_env, _seed


def report(component="both"):
    result = {"schema_version": 1, "crop_policy": "balanced-v1", "analysis_version": "import-field-3.3"}
    if component != "crop":
        result["design"] = {"photo_count": 1, "text_bucket": "short", "music": True, "cover": False,
            "available": [{"grammar": "g5", "style": "press"}, {"grammar": "g6", "style": "signal"}],
            "shown": [0, 1, 0], "selected": 0, "sequence_truncated": False}
    if component != "design":
        result["crops"] = [{"grammar": "g5", "style": "press", "slot": 0,
            "aspect_bucket": "portrait", "faces": [[2, 2, 3, 4]], "focus_grid": [7] * 64,
            "initial": [0, 0, 10000, 10000], "final": [0, 0, 10000, 10000], "edited": False}]
    return result


@pytest.fixture
def env(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'anonymous.sqlite'}", connect_args={"check_same_thread": False})
    AnonymousTelemetryReport.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)
    app = create_app(Settings(mode="test"), session_factory=factory)
    app.state.telemetry_ingress.limiter = GlobalIntakeLimit(capacity=1000)
    with TestClient(app) as client:
        yield client, app, factory, engine
    engine.dispose()


@pytest.mark.parametrize("component", ["design", "crop", "both"])
def test_real_application_persists_only_random_key_and_valid_payload(env, component):
    client, _, factory, engine = env
    payload = report(component)
    response = client.post(PATH, json=payload, headers={"X-Request-ID": "synthetic-trace", "X-Forwarded-For": "192.0.2.9"})
    assert response.status_code == 202
    assert response.json() == {"status": "ACCEPTED"}
    assert "x-request-id" not in response.headers
    assert response.headers["cache-control"] == "no-store"
    with factory() as session:
        row = session.scalar(select(AnonymousTelemetryReport))
        assert row.payload == payload
        assert row.id.version == 4
    assert {column["name"] for column in inspect(engine).get_columns("anonymous_telemetry_reports")} == {"id", "payload"}
    assert inspect(engine).get_foreign_keys("anonymous_telemetry_reports") == []


@pytest.mark.parametrize("header", ["Authorization", "Cookie", "Proxy-Authorization"])
@pytest.mark.parametrize("value", ["", "synthetic-credential"])
def test_credentials_refused_before_body_or_database(env, header, value):
    client, _, factory, _ = env
    response = client.post(PATH, content="not-json", headers={header: value})
    assert response.status_code == 403
    assert response.json() == {"status": "REJECTED"}
    assert "x-request-id" not in response.headers
    with factory() as session:
        assert session.scalar(select(AnonymousTelemetryReport)) is None


BAD_CHANGES = [
    lambda p: p.update(account_id="synthetic-user"),
    lambda p: p.update(schema_version=True),
    lambda p: p.update(schema_version=1.0),
    lambda p: p.update(crop_policy="private-policy"),
    lambda p: p.update(design=None, crops=None),
    lambda p: p["design"].update(email="synthetic@example.invalid"),
    lambda p: p["design"].update(photo_count=0),
    lambda p: p["design"].update(photo_count=True),
    lambda p: p["design"].update(selected=2),
    lambda p: p["design"].update(shown=[128]),
    lambda p: p["design"].update(shown=[0, 0]),
    lambda p: p["design"].update(shown=[0, 1] * 129),
    lambda p: p["design"].update(available=[{"grammar": "g5", "style": "personal-name"}]),
    lambda p: p["crops"][0].update(slot=12),
    lambda p: p["crops"][0].update(slot=1),
    lambda p: p["crops"][0].update(style="poster"),
    lambda p: p["crops"][0].update(faces=[[15, 0, 2, 1]]),
    lambda p: p["crops"][0].update(faces=[[0, 0, 0, 1]]),
    lambda p: p["crops"][0].update(faces=[[0, 0, 1, 1]] * 17),
    lambda p: p["crops"][0].update(focus_grid=[1] * 63),
    lambda p: p["crops"][0].update(focus_grid=[16] * 64),
    lambda p: p["crops"][0].update(focus_grid=[float("nan")] * 64),
    lambda p: p["crops"][0].update(initial=[0, 0, 10001, 1]),
    lambda p: p["crops"][0].update(final=[9999, 0, 2, 1]),
    lambda p: p["crops"][0].update(final=[0, 0, 5000, 10000]),
    lambda p: p["crops"].append(deepcopy(p["crops"][0])),
]


@pytest.mark.parametrize("change", BAD_CHANGES)
def test_strict_schema_rejections_do_not_echo_or_persist(env, change):
    client, _, factory, _ = env
    payload = report()
    change(payload)
    response = client.post(PATH, content=json.dumps(payload), headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    assert response.text == '{"status":"REJECTED"}'
    assert "x-request-id" not in response.headers
    with factory() as session:
        assert session.scalar(select(AnonymousTelemetryReport)) is None


@pytest.mark.parametrize("body", ['{"schema_version":1,"schema_version":1}', 'NaN', 'Infinity', '"raw-note"', '[]', '{'])
def test_invalid_json_is_generic(env, body):
    response = env[0].post(PATH, content=body, headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    assert response.json() == {"status": "REJECTED"}


def test_transport_bounds_and_reserved_errors_bypass_request_ids(env):
    client = env[0]
    cases = [
        client.post(PATH, content=b"x" * 32769, headers={"Content-Type": "application/json"}),
        client.post(PATH, content="{}", headers={"Content-Type": "text/plain"}),
        client.post(PATH, content="{}", headers={"Content-Type": "application/json", "Content-Encoding": "gzip"}),
        client.post(PATH + "?account=synthetic", json=report()),
        client.get(PATH), client.post(PATH + "/unknown", json=report()),
    ]
    assert [r.status_code for r in cases] == [413, 415, 415, 400, 405, 404]
    assert all("x-request-id" not in r.headers and r.json() == {"status": "REJECTED"} for r in cases)


def test_rate_limit_shared_across_peers_and_storage_cap_fail_closed(env):
    client, app, factory, _ = env
    app.state.telemetry_ingress.limiter = GlobalIntakeLimit(capacity=1, refill=0)
    assert client.post(PATH, json=report(), headers={"X-Forwarded-For": "192.0.2.1"}).status_code == 202
    assert client.post(PATH, json=report(), headers={"X-Forwarded-For": "192.0.2.2"}).status_code == 429
    app.state.telemetry_ingress.limiter = GlobalIntakeLimit(capacity=10)
    app.state.telemetry_ingress.store.row_cap = 1
    assert client.post(PATH, json=report()).status_code == 503
    with factory() as session:
        assert len(session.scalars(select(AnonymousTelemetryReport)).all()) == 1


def test_concurrent_store_cap_atomic(env):
    _, _, factory, _ = env
    store = TelemetryStore(lambda: factory, row_cap=2)
    def append(_):
        try:
            store.append(report())
            return True
        except (StoreFull, StoreUnavailable):
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(append, range(16))) == 2


def test_store_failure_sanitized_through_real_app(env, caplog):
    client, app, _, _ = env
    def failing(_):
        raise RuntimeError("synthetic-private-sql@example.invalid")
    app.state.telemetry_ingress.store.append = failing
    with caplog.at_level(logging.DEBUG):
        response = client.post(PATH, json=report())
    assert response.status_code == 503
    assert response.text == '{"status":"REJECTED"}'
    assert "x-request-id" not in response.headers
    assert "synthetic-private-sql" not in caplog.text


def test_unwired_database_is_fail_closed_and_normal_contract_unchanged():
    app = create_app(Settings(mode="test"))
    app.state.telemetry_ingress.limiter = GlobalIntakeLimit(capacity=10)
    with TestClient(app) as client:
        assert client.post(PATH, json=report()).status_code == 503
        ordinary = client.get("/missing")
        assert ordinary.status_code == 404
        assert ordinary.headers["x-request-id"] == ordinary.json()["request_id"]
        assert client.get("/v1/me/safety").status_code == 401
        privacy = client.get("/legal/privacy")
        assert privacy.status_code == 200
        for wording in ["Two independent", "off by default", "8×8", "cannot recall", "mathematical guarantee",
                        "explicit design selection or a successfully committed crop correction"]:
            assert wording in privacy.text
        assert "Version 2026-10-11" in client.get("/legal/terms").text


def test_scope_scrub_happens_before_receive_and_credential_check():
    scope = {"type": "http", "path": PATH, "raw_path": PATH.encode(), "method": "POST",
             "client": ("192.0.2.9", 1234), "server": ("server", 8000), "query_string": b"",
             "state": {"request_id": "synthetic"}, "headers": [(b"authorization", b"synthetic-token")]}
    async def receive():
        raise AssertionError("credentials must be refused before body consumption")
    sent = []
    async def send(message):
        assert scope["client"] is None and scope["server"] is None
        assert scope["headers"] == [] and scope["state"] == {} and scope["query_string"] == b""
        sent.append(message)
    asyncio.run(TelemetryIngress(TelemetryStore(lambda: None))(scope, receive, send))
    assert sent[0]["status"] == 403


def test_access_filter_suppresses_only_reserved_path():
    filter_ = TelemetryAccessFilter()
    def record(path):
        return logging.LogRecord("uvicorn.access", logging.INFO, "", 0, "%s %s %s %s %s",
                                 ("192.0.2.9", "POST", path, "1.1", 202), None)
    assert not filter_.filter(record(PATH + "?trace=synthetic"))
    assert not filter_.filter(record("/v1/telemetry/%72emanence"))
    assert not filter_.filter(record(PATH + "/wrong"))
    assert filter_.filter(record("/v1/auth/login"))
    assert filter_.filter(record("/v1/telemetry/remanence-other"))


def test_error_filter_drops_only_attributable_telemetry_tracebacks():
    def record_for(path):
        scope = {"path": path}
        try:
            raise RuntimeError("synthetic-error")
        except RuntimeError:
            return logging.LogRecord("uvicorn.error", logging.ERROR, "", 0, "error", (), sys.exc_info())
    filter_ = TelemetryErrorFilter()
    assert not filter_.filter(record_for(PATH))
    assert filter_.filter(record_for("/v1/auth/login"))


def test_chunked_oversize_and_disconnect_are_bounded_without_length():
    async def run(messages):
        scope = {"type": "http", "path": PATH, "method": "POST", "query_string": b"",
                 "headers": [(b"content-type", b"application/json")]}
        received = iter(messages)
        sent = []
        async def receive():
            return next(received)
        async def send(message):
            sent.append(message)
        await TelemetryIngress(TelemetryStore(lambda: None), GlobalIntakeLimit(10))(scope, receive, send)
        return sent[0]["status"]
    assert asyncio.run(run([{"type": "http.request", "body": b"x" * 32768, "more_body": True},
                            {"type": "http.request", "body": b"x"}])) == 413
    assert asyncio.run(run([{"type": "http.disconnect"}])) == 400


def test_global_limiter_refills_without_peer_state():
    now = [10.0]
    limit = GlobalIntakeLimit(capacity=2, refill=1, clock=lambda: now[0])
    assert limit.take() and limit.take() and not limit.take()
    now[0] += 1
    assert limit.take() and not limit.take()
    assert not any("ip" in field or "account" in field for field in vars(limit))


def test_public_allowlist_matches_current_styles():
    assert len(STYLES) == 14
    assert sum(len(styles) for styles in STYLES.values()) == 33


def test_isolated_migration_forward_backward_preserves_other_tables(tmp_path):
    file = Path(__file__).parents[1] / "migrations/versions/0012_anonymous_telemetry.py"
    spec = importlib.util.spec_from_file_location("telemetry_migration", file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.down_revision == "0011_beta_safety"
    engine = create_engine(f"sqlite:///{tmp_path / 'migration.sqlite'}")
    with engine.begin() as connection:
        connection.execute(text("CREATE TABLE sentinel (value TEXT NOT NULL)"))
        connection.execute(text("INSERT INTO sentinel VALUES ('preserved')"))
        with Operations.context(MigrationContext.configure(connection)):
            module.upgrade()
            assert {c["name"] for c in inspect(connection).get_columns("anonymous_telemetry_reports")} == {"id", "payload"}
            assert inspect(connection).get_foreign_keys("anonymous_telemetry_reports") == []
            module.downgrade()
        assert inspect(connection).get_table_names() == ["sentinel"]
        assert connection.scalar(text("SELECT value FROM sentinel")) == "preserved"
    engine.dispose()


def _postgres_worker(url, start, results):
    engine = create_engine(url)
    factory = sessionmaker(engine)
    try:
        if not start.wait(15):
            results.put("timeout")
            return
        try:
            TelemetryStore(lambda: factory, row_cap=2).append(report())
            results.put("accepted")
        except StoreFull:
            results.put("full")
        except StoreUnavailable:
            results.put("unavailable")
    finally:
        engine.dispose()


def test_postgres_cap_serializes_separate_processes_and_full_app(me_env):
    client, factory = me_env
    assert factory.kw["bind"].dialect.name == "postgresql"
    url = factory.kw["bind"].url.render_as_string(hide_password=False)
    context = multiprocessing.get_context("spawn")
    start, results = context.Event(), context.Queue()
    workers = [context.Process(target=_postgres_worker, args=(url, start, results)) for _ in range(6)]
    try:
        for worker in workers:
            worker.start()
        start.set()
        outcomes = [results.get(timeout=30) for _ in workers]
        assert outcomes.count("accepted") == 2
        assert outcomes.count("full") == 4
        for worker in workers:
            worker.join(10)
            assert worker.exitcode == 0
    finally:
        for worker in workers:
            if worker.is_alive():
                worker.terminate()
            worker.join(10)
        results.close()
    client.app.state.telemetry_ingress.limiter = GlobalIntakeLimit(10)
    client.app.state.telemetry_ingress.store.row_cap = 2
    assert client.post(PATH, json=report()).status_code == 503
    with factory() as session:
        rows = session.scalars(select(AnonymousTelemetryReport)).all()
        assert len(rows) == 2 and all(row.id.version == 4 for row in rows)


def test_postgres_additive_migration_roundtrip_keeps_synthetic_account(me_env):
    from alembic import command
    from alembic.config import Config
    from remanence.users.models import User
    client, factory = me_env
    seed = _seed(client)
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    command.downgrade(config, "0011_beta_safety")
    engine = factory.kw["bind"]
    assert "anonymous_telemetry_reports" not in inspect(engine).get_table_names()
    with factory() as session:
        assert session.scalar(select(User)).handle_normalized == "alice"
    command.upgrade(config, "head")
    assert {c["name"] for c in inspect(engine).get_columns("anonymous_telemetry_reports")} == {"id", "payload"}
    assert client.get("/v1/me", headers={"Authorization": "Bearer " + seed["access_token"]}).status_code == 200


def test_telemetry_dispatch_does_not_allocate_request_id(env, monkeypatch):
    def forbidden():
        raise AssertionError("telemetry must not allocate a trace identifier")
    monkeypatch.setattr("remanence.api.problems.uuid4", forbidden)
    assert env[0].post(PATH, json=report()).status_code == 202


def test_android_wire_selection_with_committed_correction(env):
    # Exact T182 public fixture shape: multiple photos, A/B/A appearances and
    # a single analyzed/corrected slot. No source identity enters this payload.
    payload = report()
    payload["design"]["photo_count"] = 3
    payload["design"]["available"][1] = {"grammar": "g5", "style": "blue-letter"}
    payload["crops"][0].update(aspect_bucket="square", faces=[[4, 4, 3, 3]],
        focus_grid=list(range(16)) * 4, final=[1000, 2000, 8000, 7000], edited=True)
    assert env[0].post(PATH, json=payload).status_code == 202


def test_response_send_failure_does_not_allocate_request_id_or_expose_scope(monkeypatch):
    app = create_app(Settings(mode="test"))
    app.state.telemetry_ingress.limiter = GlobalIntakeLimit(10)
    def forbidden():
        raise AssertionError("telemetry send failure must not allocate a request ID")
    monkeypatch.setattr("remanence.api.problems.uuid4", forbidden)
    body = json.dumps(report()).encode()
    scope = {"type": "http", "http_version": "1.1", "asgi": {"version": "3.0"},
        "method": "POST", "scheme": "http", "path": PATH, "raw_path": PATH.encode(),
        "query_string": b"", "root_path": "", "client": ("192.0.2.9", 9), "server": ("test", 80),
        "headers": [(b"content-type", b"application/json")], "state": {}}
    messages = []
    failed = False
    async def receive():
        return {"type": "http.request", "body": body}
    async def send(message):
        nonlocal failed
        if not failed:
            failed = True
            raise RuntimeError("synthetic-send-failure")
        messages.append(message)
    with pytest.raises(RuntimeError, match="synthetic-send-failure"):
        asyncio.run(app(scope, receive, send))
    # Starlette considers response started before transport send; no fallback
    # can be delivered. Its rethrow still sees only the scrubbed shared scope.
    assert messages == []
    assert scope["headers"] == [] and scope["state"] == {}
    assert scope["client"] is None and scope["server"] is None


def test_outer_telemetry_error_handler_is_generic_if_response_not_started(monkeypatch):
    from starlette.requests import Request
    app = create_app(Settings(mode="test"))
    def forbidden():
        raise AssertionError("telemetry fallback must not allocate a request ID")
    monkeypatch.setattr("remanence.api.problems.uuid4", forbidden)
    request = Request({"type": "http", "path": PATH, "headers": [], "state": {}})
    response = app.exception_handlers[Exception](request, RuntimeError("synthetic-error"))
    assert response.status_code == 503
    assert "x-request-id" not in response.headers
    assert response.body == b'{"status":"REJECTED"}'
