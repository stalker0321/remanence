"""Composition gates: both independent routers/cache layers in the real app."""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, text
from tink import tink_config

from composed_support import publication, world
from remanence.api.problems import PROBLEM_BODY_FIELDS
from remanence.send_access.models import SendPublication
from remanence.send_access.policy import SendAccessMode as Mode

pytest_plugins = ("test_session_repository_create",)
PATHS = ("/v1/me/send-access", "/v1/capsules/outgoing", "/v1/recipients/recent")


@pytest.fixture(scope="module", autouse=True)
def tink_ready():
    tink_config.register()


def test_both_cache_layers_auth_dependencies_and_slash_survive_composition(session_factory, monkeypatch):
    app, _, _, headers = world(session_factory, monkeypatch)
    with TestClient(app, raise_server_exceptions=False) as client:
        for path in PATHS:
            for suffix, credentials, expected in (("", {}, 401), ("?owner_id=other", headers, 422), ("/", headers, 307)):
                reply = client.get(path + suffix, headers=credentials, follow_redirects=False)
                assert reply.status_code == expected
                assert reply.headers["cache-control"] == "private, no-store"
            assert client.get(path, headers=headers).status_code == 200
        app.state.settings = app.state.settings.model_copy(update={"send_access_mode": Mode.OFF})
        assert [client.get(path, headers=headers).status_code for path in PATHS] == [404, 200, 200]
        app.state.session_factory = None
        for path in PATHS:
            reply = client.get(path, headers=headers)
            assert reply.status_code == 503 and reply.headers["cache-control"] == "private, no-store"
            assert set(reply.json()) == PROBLEM_BODY_FIELDS
            assert reply.json()["code"] == ("SEND_ACCESS_UNAVAILABLE" if path == PATHS[0] else "INTERNAL_UNAVAILABLE")


def test_real_publications_history_nulls_recents_and_both_cancels_do_not_refund(session_factory, tmp_path, monkeypatch):
    app, sender, recipient, headers = world(session_factory, monkeypatch)
    with TestClient(app) as client:
        own = publication(session_factory, tmp_path, sender, sender)
        other = publication(session_factory, tmp_path, sender, recipient)
        pending = publication(session_factory, tmp_path, sender, None, "v2")
        access_before = client.get(PATHS[0], headers=headers).json()
        assert access_before["free"] == {"self": {"limit": 2, "used": 1, "remaining": 1},
                                         "other": {"limit": 2, "used": 2, "remaining": 0}}
        rows = client.get(PATHS[1], headers=headers).json()["items"]
        assert len(rows) == 3
        pending_row = next(row for row in rows if row["protocol_version"] == 2)
        assert pending_row["published_at"] is None and pending_row["recipient"]["user_id"] is None
        assert pending_row["cancel_action"] == "CANCEL_PENDING_V2"
        recent = client.get(PATHS[2], headers=headers).json()["items"]
        assert len(recent) == 1 and recent[0]["user_id"] == str(recipient.user.id)
        assert client.post(f"/v1/capsules/{other.id}/revoke", headers=headers).status_code == 200
        assert client.post(f"/v2/pending-deliveries/{pending.id}/cancel", headers=headers).status_code == 200
        rows = client.get(PATHS[1], headers=headers).json()["items"]
        states = {row["capsule_id"]: row["status"] for row in rows}
        assert states == {str(own.capsule_id): "READY", str(other.capsule_id): "REVOKED", str(pending.capsule_id): "CANCELLED"}
        assert client.get(PATHS[0], headers=headers).json() == access_before
        assert client.get(PATHS[2], headers=headers).json()["items"] == recent
    with session_factory() as observer:
        assert observer.scalar(text("SELECT version_num FROM alembic_version")) == "0017_send_access"
        assert observer.scalar(select(func.count()).select_from(SendPublication)) == 3
