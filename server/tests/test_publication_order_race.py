"""Real-PostgreSQL regression for publication order versus incoming cursors."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier, BrokenBarrierError, Event

import pytest
import tink
from tink import tink_config

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.finalize_service import CapsuleFinalizeService
from remanence.capsules.incoming_query_service import IncomingCapsuleQueryService

from test_capsule_finalize_service import (
    _NOW,
    _add_draft,
    _envelope,
    _payloads,
    _protocol_blobs,
    _seed_user,
    _sign,
    _signing_pair,
    _statement_bytes,
    _store_all,
)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def test_late_commit_cannot_be_permanently_hidden_by_earlier_ready_at(
    session_factory, tmp_path
):
    """Fence the old B-before-A schedule with recipient publication order."""

    payloads = _payloads()
    sender_private, sender_public = _signing_pair()
    _recipient_private, recipient_public = _signing_pair()
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender", signing_public=sender_public)
        recipient, recipient_bundle = _seed_user(
            session, "recipient", signing_public=recipient_public
        )
        capsule_a = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            payloads=payloads,
        )
        capsule_b = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            payloads=payloads,
        )
        from remanence.storage import LocalFileBlobStore

        store = LocalFileBlobStore(tmp_path / "blobs")
        _store_all(session, store, capsule_a, payloads)
        _store_all(session, store, capsule_b, payloads)
        session.commit()
        statement_a = _statement_bytes(capsule_a, _protocol_blobs(session, capsule_a))
        statement_b = _statement_bytes(capsule_b, _protocol_blobs(session, capsule_b))
        signature_a = _sign(sender_private, statement_a)
        signature_b = _sign(sender_private, statement_b)
        envelope_a = _envelope(recipient_bundle.id, b"envelope-a")
        envelope_b = _envelope(recipient_bundle.id, b"envelope-b")
        capsule_a_id = capsule_a.id
        capsule_b_id = capsule_b.id
        recipient_id = recipient.id
        sender_id = sender.id
        sender_bundle_id = sender_bundle.id

    a_flushed = Barrier(2)
    allow_a_commit = Event()
    b_started = Event()
    a_committed = Event()
    b_committed = Event()

    def finalize_a_then_pause():
        with session_factory() as session:
            result = CapsuleFinalizeService(session, store).finalize(
                authenticated_sender_user_id=sender_id,
                capsule_id=capsule_a_id,
                statement=statement_a,
                signature=signature_a,
                sender_key_bundle_id=sender_bundle_id,
                envelope=envelope_a,
                now=_NOW,
            )
            a_flushed.wait(timeout=10)
            allow_a_commit.wait(timeout=10)
            session.commit()
            a_committed.set()
            return result

    def finalize_b_and_commit():
        b_started.set()
        with session_factory() as session:
            result = CapsuleFinalizeService(session, store).finalize(
                authenticated_sender_user_id=sender_id,
                capsule_id=capsule_b_id,
                statement=statement_b,
                signature=signature_b,
                sender_key_bundle_id=sender_bundle_id,
                envelope=envelope_b,
                now=_NOW + timedelta(seconds=1),
            )
            session.commit()
            b_committed.set()
            return result

    with ThreadPoolExecutor(max_workers=2) as executor:
        future_a = executor.submit(finalize_a_then_pause)
        try:
            a_flushed.wait(timeout=10)
        except BrokenBarrierError:
            # Surface a worker failure at the actual finalize call rather
            # than masking it with the coordinating barrier timeout.
            future_a.result(timeout=1)
            raise
        future_b = executor.submit(finalize_b_and_commit)
        assert b_started.wait(timeout=10)
        assert not b_committed.is_set()
        allow_a_commit.set()
        result_a = future_a.result(timeout=20)
        result_b = future_b.result(timeout=20)
        assert result_a.capsule_id == capsule_a_id
        assert result_b.capsule_id == capsule_b_id
        assert a_committed.is_set()
        assert b_committed.is_set()

    with session_factory() as session:
        page_after_b = IncomingCapsuleQueryService(session).list_incoming(
            authenticated_recipient_user_id=recipient_id,
            limit=1,
        )
        assert [item.capsule_id for item in page_after_b.items] == [capsule_a_id]
        assert page_after_b.next_cursor is not None
        saved_cursor = page_after_b.next_cursor

    with session_factory() as session:
        continuation = IncomingCapsuleQueryService(session).list_incoming(
            authenticated_recipient_user_id=recipient_id,
            cursor=saved_cursor,
            limit=10,
        )
        # Publication order is commit-safe: A owns the recipient lock until
        # its commit, so B cannot publish a cursor position before A.
        assert [item.capsule_id for item in continuation.items] == [capsule_b_id]
