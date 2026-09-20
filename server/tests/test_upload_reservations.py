"""Unit tests for bounded per-account upload reservations."""

from uuid import uuid4

import pytest

from remanence.capsules.upload_reservations import (
    UploadReservationError,
    UploadReservationManager,
)


def _manager(*, max_bytes: int, max_uploads: int) -> UploadReservationManager:
    return UploadReservationManager(
        max_bytes_per_account=max_bytes,
        max_uploads_per_account=max_uploads,
    )


def test_constructor_rejects_nonpositive_bounds() -> None:
    with pytest.raises(ValueError):
        _manager(max_bytes=0, max_uploads=1)
    with pytest.raises(ValueError):
        _manager(max_bytes=1, max_uploads=0)


def test_reserve_rejects_invalid_arguments() -> None:
    manager = _manager(max_bytes=10, max_uploads=2)
    with pytest.raises(ValueError):
        manager.reserve(owner_user_id="not-a-uuid", token=("a",), size=1)  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        manager.reserve(owner_user_id=uuid4(), token=("a",), size=0)
    with pytest.raises(ValueError):
        manager.reserve(owner_user_id=uuid4(), token=("a",), size=1.5)  # type: ignore[arg-type]


def test_byte_budget_is_bounded_and_released() -> None:
    owner = uuid4()
    manager = _manager(max_bytes=10, max_uploads=5)
    first = manager.reserve(owner_user_id=owner, token=("a",), size=6)
    assert manager.outstanding_bytes(owner) == 6
    with pytest.raises(UploadReservationError):
        manager.reserve(owner_user_id=owner, token=("b",), size=5)
    second = manager.reserve(owner_user_id=owner, token=("b",), size=4)
    assert manager.outstanding_bytes(owner) == 10
    first.release()
    assert manager.outstanding_bytes(owner) == 4
    second.release()
    assert manager.outstanding_bytes(owner) == 0
    assert manager.outstanding_uploads(owner) == 0


def test_upload_count_is_bounded_per_account() -> None:
    owner = uuid4()
    manager = _manager(max_bytes=1000, max_uploads=1)
    first = manager.reserve(owner_user_id=owner, token=("a",), size=1)
    with pytest.raises(UploadReservationError):
        manager.reserve(owner_user_id=owner, token=("b",), size=1)
    first.release()
    manager.reserve(owner_user_id=owner, token=("b",), size=1).release()


def test_budget_is_isolated_per_account() -> None:
    first_owner = uuid4()
    second_owner = uuid4()
    manager = _manager(max_bytes=5, max_uploads=1)
    manager.reserve(owner_user_id=first_owner, token=("a",), size=5)
    held = manager.reserve(owner_user_id=second_owner, token=("a",), size=5)
    assert manager.outstanding_bytes(second_owner) == 5
    held.release()
    assert manager.outstanding_bytes(second_owner) == 0
    assert manager.outstanding_bytes(first_owner) == 5


def test_same_token_shares_one_hold_and_releases_once() -> None:
    owner = uuid4()
    manager = _manager(max_bytes=5, max_uploads=1)
    first = manager.reserve(owner_user_id=owner, token=("same",), size=5)
    retry = manager.reserve(owner_user_id=owner, token=("same",), size=5)
    assert manager.outstanding_uploads(owner) == 1
    assert manager.outstanding_bytes(owner) == 5
    first.release()
    assert manager.outstanding_uploads(owner) == 1
    assert manager.outstanding_bytes(owner) == 5
    retry.release()
    assert manager.outstanding_uploads(owner) == 0
    assert manager.outstanding_bytes(owner) == 0


def test_release_is_idempotent() -> None:
    owner = uuid4()
    manager = _manager(max_bytes=5, max_uploads=1)
    reservation = manager.reserve(owner_user_id=owner, token=("a",), size=5)
    reservation.release()
    reservation.release()
    assert manager.outstanding_bytes(owner) == 0
    assert repr(reservation) == "UploadReservation(<opaque>)"
    assert reservation.size == 5
    assert reservation.owner_user_id == owner


def test_outstanding_unknown_account_is_zero() -> None:
    manager = _manager(max_bytes=5, max_uploads=1)
    assert manager.outstanding_bytes(uuid4()) == 0
    assert manager.outstanding_uploads(uuid4()) == 0
