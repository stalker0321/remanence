"""R8: finalize INTERNAL_ERROR paths stay redacted externally but observable internally."""

import logging
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from tink import tink_config

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()

from remanence.api.capsules import get_blob_store, get_db_session
from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from test_capsule_draft_endpoint import _assert_problem
from test_capsule_finalize_endpoint import _finalize_body
from test_capsule_finalize_service import (
    _assert_error,
    _finalize,
    _ready_world,
)


def _logged_text(caplog: pytest.LogCaptureFixture) -> str:
    return "\n".join(record.getMessage() for record in caplog.records)


def test_unexpected_blob_stat_error_stays_redacted_but_logs_diagnostics(
    session_factory, tmp_path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """An unexpected storage failure still surfaces INTERNAL_ERROR, while the
    internal log retains stage, capsule id, and exception type — and nothing
    carrying request payload bytes."""
    canary = "handler-canary-unexpected-stat"

    class _BoomStore:
        def __init__(self, delegate):
            self._delegate = delegate

        def stat(self, key: str):
            raise RuntimeError(canary)

        def __getattr__(self, name: str):
            return getattr(self._delegate, name)

    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        boom = _BoomStore(world["store"])
        with caplog.at_level(logging.ERROR, logger="remanence.capsules.finalize_service"):
            _assert_error(
                lambda: _finalize(
                    session,
                    boom,
                    sender_id=world["sender"].id,
                    capsule=world["capsule"],
                    statement=world["statement"],
                    signature_bytes=world["signature"],
                    envelope=world["envelope"],
                ),
                "INTERNAL_ERROR",
            )
        records = [
            record
            for record in caplog.records
            if record.name == "remanence.capsules.finalize_service"
        ]
        assert records, "unexpected blob failure must emit an internal diagnostic"
        text = _logged_text(caplog)
        assert "stage=blob_stat" in text
        assert str(world["capsule"].id) in text
        assert "exc_type=RuntimeError" in text
        assert canary not in text
        assert world["statement"].hex() not in text
        session.rollback()


def test_outer_finalize_guard_logs_unexpected_failure(
    session_factory, tmp_path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """A failure escaping the finalize body still surfaces INTERNAL_ERROR
    with a stage=finalize diagnostic naming the exception type."""
    canary = "handler-canary-outer-guard"

    def _boom_scalar(*_args, **_kwargs):
        raise RuntimeError(canary)

    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        monkeypatch.setattr(session, "scalar", _boom_scalar)
        with caplog.at_level(logging.ERROR, logger="remanence.capsules.finalize_service"):
            _assert_error(
                lambda: _finalize(
                    session,
                    world["store"],
                    sender_id=world["sender"].id,
                    capsule=world["capsule"],
                    statement=world["statement"],
                    signature_bytes=world["signature"],
                    envelope=world["envelope"],
                ),
                "INTERNAL_ERROR",
            )
        text = _logged_text(caplog)
        assert "stage=finalize" in text
        assert str(world["capsule"].id) in text
        assert "exc_type=RuntimeError" in text
        assert canary not in text
        session.rollback()


def test_handler_fallback_keeps_redacted_response_and_logs_request_id(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """An unexpected failure past parsing still returns the redacted
    INTERNAL_ERROR problem, while the handler log carries the request id
    and exception type instead of request contents."""
    canary = "handler-canary-unexpected-handler"

    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _BoomSession:
        def begin(self):
            raise RuntimeError(canary)

    app.dependency_overrides[get_db_session] = lambda: _BoomSession()
    app.state.blob_store = object()
    client = TestClient(app)

    with caplog.at_level(logging.ERROR, logger="remanence.api.capsules"):
        response = client.post(
            f"/v1/capsules/{uuid4()}/finalize",
            content=_finalize_body(),
        )
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    request_id = response.headers["x-request-id"]
    text = _logged_text(caplog)
    assert f"request_id={request_id}" in text
    assert "exc_type=RuntimeError" in text
    assert canary not in text
    assert "signed_publish_statement" not in text
