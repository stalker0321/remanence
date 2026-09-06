"""PostgreSQL tests for the recipient-only tombstone feed query."""

from __future__ import annotations

import inspect
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import uuid4

import pytest

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.tombstone_cursor import encode_tombstone_cursor
from remanence.capsules.tombstone_query_service import (
    TombstoneQueryError,
    TombstoneQueryService,
)

from test_capsule_abort_service import _NOW, _seed_user


def _assert_error(call, code: str) -> None:
    with pytest.raises(TombstoneQueryError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "tombstone query failed"
    assert caught.value.__cause__ is None
    assert caught.value.__context__ is None


def _add_tombstone(session, *, sender, sender_bundle, recipient, recipient_bundle, sequence: int, ready_at: datetime):
    capsule = Capsule(
        id=uuid4(),
        sender_user_id=sender.id,
        recipient_user_id=recipient.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=recipient_bundle.id,
        protocol_version=1,
        state=CapsuleState.REVOKED,
        signed_statement=b"signed-statement",
        signed_statement_sha256=b"\x01" * 32,
        publish_signature=b"\x02" * 69,
        created_at=_NOW,
        ready_at=ready_at,
        tombstone_sequence=sequence,
        revoked_at=_NOW + timedelta(minutes=sequence),
        draft_expires_at=_NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    return capsule


def _query(session, recipient_id, *, cursor=None, limit=50):
    return TombstoneQueryService(session).list_tombstones(
        authenticated_recipient_user_id=recipient_id,
        cursor=cursor,
        limit=limit,
    )


def test_tombstones_are_recipient_scoped_ordered_and_paginated(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        other, other_bundle = _seed_user(session, "other")
        first = _add_tombstone(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            sequence=1,
            ready_at=_NOW,
        )
        second = _add_tombstone(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            sequence=2,
            ready_at=_NOW + timedelta(seconds=1),
        )
        _add_tombstone(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=other,
            recipient_bundle=other_bundle,
            sequence=1,
            ready_at=_NOW,
        )
        session.commit()

        page = _query(session, recipient.id, limit=1)
        assert [item.capsule_id for item in page.items] == [first.id]
        assert page.items[0].revoked_at == _NOW + timedelta(minutes=1)
        assert page.has_more is True
        assert page.next_cursor == encode_tombstone_cursor(
            tombstone_sequence=1,
            capsule_id=first.id,
        )

        continuation = _query(session, recipient.id, cursor=page.next_cursor, limit=10)
        assert [item.capsule_id for item in continuation.items] == [second.id]
        assert continuation.has_more is False
        assert continuation.next_cursor == encode_tombstone_cursor(
            tombstone_sequence=2,
            capsule_id=second.id,
        )

        empty = _query(session, recipient.id, cursor=continuation.next_cursor, limit=10)
        assert empty.items == ()
        assert empty.has_more is False
        assert empty.next_cursor == continuation.next_cursor


def test_tombstone_query_rejects_forged_foreign_anchor_and_bad_inputs(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        other, other_bundle = _seed_user(session, "other")
        foreign = _add_tombstone(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=other,
            recipient_bundle=other_bundle,
            sequence=1,
            ready_at=_NOW,
        )
        session.commit()
        foreign_cursor = encode_tombstone_cursor(
            tombstone_sequence=1,
            capsule_id=foreign.id,
        )
        service = TombstoneQueryService(session)
        _assert_error(
            lambda: service.list_tombstones(
                authenticated_recipient_user_id=recipient.id,
                cursor=foreign_cursor,
                limit=1,
            ),
            "VALIDATION_FAILED",
        )
        for value in (0, 101, -1, True, False, 1.0, "1", None):
            _assert_error(
                lambda value=value: service.list_tombstones(
                    authenticated_recipient_user_id=recipient.id,
                    limit=value,
                ),
                "VALIDATION_FAILED",
            )
        _assert_error(
            lambda: service.list_tombstones(
                authenticated_recipient_user_id=str(recipient.id),  # type: ignore[arg-type]
            ),
            "VALIDATION_FAILED",
        )


def test_tombstone_query_does_not_write_or_lock_and_errors_are_redacted(
    session_factory, monkeypatch: pytest.MonkeyPatch
):
    with session_factory() as session:
        recipient, _recipient_bundle = _seed_user(session, "recipient")
        session.commit()

        def forbidden(*_args: Any, **_kwargs: Any) -> None:
            raise AssertionError("tombstone query must not commit or rollback")

        monkeypatch.setattr(session, "commit", forbidden)
        monkeypatch.setattr(session, "rollback", forbidden)
        source = inspect.getsource(TombstoneQueryService)
        assert "with_for_update" not in source
        assert "CapsuleBlob" not in source
        assert "payload" not in source
        _assert_error(
            lambda: TombstoneQueryService(session).list_tombstones(
                authenticated_recipient_user_id=recipient.id,
                cursor="secret-cursor",
            ),
            "VALIDATION_FAILED",
        )
