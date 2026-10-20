"""Real PG + HTTP boundary: denial, rollback, response validation and lost reply."""

import pytest
from sqlalchemy import delete, event, func, select
from sqlalchemy.exc import SQLAlchemyError
from tink import tink_config

from remanence.api import capsules
from remanence.api.problems import PROBLEM_BODY_FIELDS
from remanence.pending import api as pending_api
from remanence.pending.models import GiftLocator
from remanence.send_access.models import AccountSendAccess, SendPublication
from send_access_http_support import bearer, post, publication_client
from send_access_support import actor, publish, source

pytest_plugins = ("test_session_repository_create",)


@pytest.fixture(scope="module", autouse=True)
def register_tink():
    tink_config.register()


@pytest.mark.parametrize("arm", ["v1", "v2"])
@pytest.mark.parametrize("fault", ["limit", "access", "dto", "commit"])
def test_http_denial_and_late_failure_leave_no_partial_publication(session_factory, tmp_path, monkeypatch, arm, fault):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, arm, sender=sender)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
        if fault == "limit":
            for _ in range(2):
                spent = source(seed, tmp_path, "v1", sender=sender)
                seed.commit()
                publish(seed, spent)
                seed.commit()
        if fault == "access":
            seed.execute(delete(AccountSendAccess).where(AccountSendAccess.account_id == sender.user.id))
            seed.commit()
    with publication_client(session_factory, item, monkeypatch) as client:
        hits = []
        if fault == "dto":
            def bad_dto(*args, **kwargs):
                hits.append(1)
                raise ValueError("synthetic serialization failure")
            monkeypatch.setattr(capsules if arm == "v1" else pending_api, "_accepted_finalize_response", bad_dto)
        engine = session_factory.kw["bind"]
        def fail_commit(connection):
            hits.append(1)
            raise SQLAlchemyError("synthetic pre-DBAPI commit failure")
        if fault == "commit":
            event.listen(engine, "commit", fail_commit)
        try:
            response = post(client, item, headers)
        finally:
            if fault == "commit":
                event.remove(engine, "commit", fail_commit)
        status, code = {"limit": (409, "SEND_LIMIT_REACHED"), "access": (503, "SEND_ACCESS_UNAVAILABLE"),
                        "dto": (500, "INTERNAL_ERROR"), "commit": (500, "INTERNAL_ERROR")}[fault]
        assert (response.status_code, response.json()["code"]) == (status, code), response.text
        if arm == "v1":
            assert set(response.json()) == PROBLEM_BODY_FIELDS
            assert response.json()["retryable"] == (fault == "access")
        else:
            assert set(response.json()) == {"type", "title", "status", "code"}
        if fault in ("dto", "commit"):
            assert hits == [1]
        with session_factory() as check:
            row = item.row(check)
            assert row.state == "DRAFT" and row.send_accounting_version is None
            assert check.get(SendPublication, item.capsule_id) is None
            assert check.scalar(select(func.count()).select_from(GiftLocator)) == 0
            if arm == "v2":
                assert row.vault_ciphertext is None


@pytest.mark.parametrize("arm", ["v1", "v2"])
def test_committed_publication_with_lost_http_reply_replays_without_spending(session_factory, tmp_path, monkeypatch, arm):
    with session_factory() as seed:
        sender = actor(seed)
        item = source(seed, tmp_path, arm, sender=sender)
        headers, _ = bearer(seed, sender.user.id)
        seed.commit()
    dropped = []

    class DropFirstReply:
        def __init__(self, app):
            self.app = app

        async def __call__(self, scope, receive, send):
            async def lose(message):
                if message["type"] == "http.response.start" and not dropped:
                    dropped.append(message["status"])
                    raise RuntimeError("synthetic transport loss AFTER route commit")
                await send(message)
            await self.app(scope, receive, lose)

    with publication_client(session_factory, item, monkeypatch) as client:
        # Stack is already built by lifespan: wrap only this synthetic client.
        client.app.middleware_stack = DropFirstReply(client.app.middleware_stack)
        lost = post(client, item, headers)
        assert lost.status_code == 500 and dropped == [201 if arm == "v1" else 200]
        with session_factory() as observer:
            receipt = observer.get(SendPublication, item.capsule_id)
            assert receipt is not None and receipt.free_slot == 1
            original = (receipt.published_at, receipt.publication_sha256, receipt.funding)
        replay = post(client, item, headers)
        assert replay.status_code == 200, replay.text
        assert set(replay.json()) == ({"capsule_id", "state", "ready_at"} if arm == "v1" else
                                     {"delivery_id", "state", "locator_code", "claim_expires_at", "hint"})
        with session_factory() as observer:
            receipt = observer.get(SendPublication, item.capsule_id)
            assert observer.scalar(select(func.count()).select_from(SendPublication)) == 1
            assert (receipt.published_at, receipt.publication_sha256, receipt.funding) == original
