from fastapi.testclient import TestClient

from remanence.api.rate_limit import TokenBucketLimiter
from remanence.main import create_app
from remanence.settings import AppMode, Settings


def _client() -> TestClient:
    return TestClient(create_app(settings=Settings(mode=AppMode.TEST)), raise_server_exceptions=False)


def test_login_bursts_are_limited_per_client_with_retry_after():
    client = _client()
    statuses = [client.post("/v1/auth/login", json={}).status_code for _ in range(10)]
    assert 429 not in statuses
    limited = client.post("/v1/auth/login", json={})
    assert limited.status_code == 429
    assert limited.json()["code"] == "RATE_LIMITED"
    assert int(limited.headers["retry-after"]) >= 1


def test_auth_routes_share_one_budget_but_directory_has_its_own():
    client = _client()
    for _ in range(10):
        client.post("/v1/auth/register", json={})
    assert client.post("/v1/auth/login", json={}).status_code == 429
    assert client.get("/v1/directory/handles/alice").status_code != 429


def test_registration_setup_keeps_its_reason_shape():
    client = _client()
    client.app.state.settings = client.app.state.settings.model_copy(
        update={"google_sign_in_enabled": True, "recovery_enabled": True}
    )
    path = "/v2/registration/reservations/00000000-0000-4000-8000-000000000000"
    for _ in range(15):
        client.get(path)
    limited = client.get(path)
    assert limited.status_code == 429
    assert limited.json() == {"reason": "RATE_LIMITED"}


def test_bucket_refills_over_time_and_keys_are_independent():
    now = [0.0]
    limiter = TokenBucketLimiter(per_minute=60, burst=2, clock=lambda: now[0])
    assert limiter.check("a") == (True, 0)
    assert limiter.check("a") == (True, 0)
    assert limiter.check("a") == (False, 1)
    assert limiter.check("b") == (True, 0)
    now[0] += 1.0
    assert limiter.check("a") == (True, 0)


def test_recovery_test_page_is_off_by_default():
    client = _client()
    client.app.state.settings = client.app.state.settings.model_copy(update={"recovery_enabled": True})
    assert client.get("/recovery-test/").status_code == 404


def test_password_auth_closes_once_atomic_registration_is_required():
    client = _client()
    client.app.state.settings = client.app.state.settings.model_copy(update={"registration_atomic_required": True})
    assert client.post("/v1/auth/login", json={}).status_code == 404
    assert client.post("/v1/auth/register", json={}).status_code == 404
