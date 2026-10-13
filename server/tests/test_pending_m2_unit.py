"""T185 M2 unit tests: flags, lock keys, routing, sealed routes, providers.

No database, no network. Synthetic data only.
"""

from __future__ import annotations

import hashlib
import os
import uuid
from collections.abc import Iterator

import pytest
from pydantic import ValidationError
from tink import tink_config

from remanence.pending.locator_store import DEFAULT_ISSUANCE_CAP, LocatorStoreError, code_hmac
from remanence.pending.models import (
    DeliveryAddressBinding,
    GiftLocator,
    PendingSendRoute,
    UsedLocatorTombstone,
)
from remanence.pending.providers import FailClosedDispatcher, MockDispatcher, ProviderError
from remanence.pending.registry import advisory_key, lookup_tag
from remanence.pending.routing import (
    RouteDecision,
    RoutingError,
    decide_route,
    open_destination,
    seal_destination,
)
from remanence.settings import AppMode, Settings


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)
    tink_config.register()
    yield


# --- settings flags ------------------------------------------------------------


def test_pending_flags_default_off() -> None:
    settings = Settings(mode=AppMode.TEST)
    assert settings.pending_protocol_enabled is False
    assert settings.pending_sends_enabled is False
    assert settings.pending_claims_enabled is False
    assert settings.pending_locator_issuance_enabled is False
    assert settings.pending_kms_key_id is None


def test_pending_subflag_without_master_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(mode=AppMode.TEST, pending_sends_enabled=True)


def test_pending_prod_without_custody_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(
            mode=AppMode.PROD,
            database_url="postgresql+psycopg://u:p@127.0.0.1:1/db",
            blob_root="/tmp/blobs",
            pending_protocol_enabled=True,
        )


def test_pending_prod_with_allowlisted_custody_accepted() -> None:
    settings = Settings(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://u:p@127.0.0.1:1/db",
        blob_root="/tmp/blobs",
        pending_protocol_enabled=True,
        pending_kms_key_id="kms-1",
        pending_allowed_kms_key_ids=["kms-1"],
    )
    assert settings.pending_kms_key_id == "kms-1"


# --- advisory lock keys ----------------------------------------------------------


def test_advisory_keys_signed_bigint_range() -> None:
    lo = -(2**63)
    hi = 2**63 - 1
    high_bit = 0
    for i in range(200):
        key = advisory_key("email", hashlib.sha256(f"addr-{i}".encode()).digest())
        assert lo <= key <= hi
        if key < 0:
            high_bit += 1
    # Both high-bit (negative) and low-bit (non-negative) digests occur;
    # an unsigned conversion would overflow PostgreSQL bigint for ~half.
    assert high_bit > 0


# --- routing decisions ------------------------------------------------------------


def test_decide_route_matrix() -> None:
    owner, bundle = uuid.uuid4(), uuid.uuid4()
    from remanence.pending.registry import LiveBinding

    v1 = decide_route(
        destination_kind="email",
        live=LiveBinding(owner_user_id=owner, revision=2),
        active_bundle_id=bundle,
    )
    assert v1.mode == "EXISTING_USER_V1" and v1.user_id == owner
    pending = decide_route(destination_kind="email", live=None, active_bundle_id=None)
    assert pending.mode == "PENDING_V2" and pending.delivery_target_id is not None
    # Verified binding without a usable bundle: error, never silent escrow.
    with pytest.raises(RoutingError):
        decide_route(
            destination_kind="email",
            live=LiveBinding(owner_user_id=owner, revision=2),
            active_bundle_id=None,
        )
    # Retired address: explicit error, never absent->pending fallback.
    with pytest.raises(RoutingError):
        decide_route(
            destination_kind="email", live=None, active_bundle_id=None, address_retired=True
        )
    with pytest.raises(RoutingError):
        decide_route(
            destination_kind="email",
            live=LiveBinding(owner_user_id=owner, revision=1),
            active_bundle_id=bundle,
            sender_blocked=True,
        )


# --- sealed destinations ------------------------------------------------------------


def test_sealed_destination_roundtrip_and_prod_refusal() -> None:
    key = bytes([0x0C] * 32)
    sealed = seal_destination(normalized="a42@example.test", key_32=key, mode="test")
    assert b"a42@example.test" not in sealed
    assert open_destination(sealed=sealed, key_32=key) == "a42@example.test"
    with pytest.raises(RoutingError):
        seal_destination(normalized="a42@example.test", key_32=key, mode="prod")
    with pytest.raises(RoutingError):
        open_destination(sealed=b"garbage", key_32=key)


# --- providers ----------------------------------------------------------------------


def test_fail_closed_dispatcher_never_approves() -> None:
    dispatcher = FailClosedDispatcher()
    with pytest.raises(ProviderError):
        dispatcher.start(attempt_id="a", stored_destination="b", locale="en")
    with pytest.raises(ProviderError):
        dispatcher.check(attempt_id="a", provider_reference="r", code="1")


def test_mock_dispatcher_records_lengths_not_values() -> None:
    dispatcher = MockDispatcher(approval_code="87654321")
    reference = dispatcher.start(
        attempt_id="attempt-1", stored_destination="+380501234567", locale="uk"
    )
    assert dispatcher.check(attempt_id="attempt-1", provider_reference=reference, code="87654321") == "approved"
    assert dispatcher.check(attempt_id="attempt-1", provider_reference=reference, code="00000000") == "rejected"
    assert dispatcher.check(attempt_id="attempt-1", provider_reference="wrong", code="87654321") == "rejected"
    (record,) = dispatcher.dispatched
    assert record.destination_length == len("+380501234567")


# --- models metadata ------------------------------------------------------------------


def test_m2_model_tables_and_route_hides_plaintext() -> None:
    assert DeliveryAddressBinding.__tablename__ == "delivery_address_bindings"
    assert PendingSendRoute.__tablename__ == "pending_send_routes"
    assert GiftLocator.__tablename__ == "gift_locators"
    assert UsedLocatorTombstone.__tablename__ == "used_locator_tombstones"
    route_columns = set(PendingSendRoute.__table__.columns.keys())
    assert "sealed_destination" in route_columns
    assert "normalized_destination" not in route_columns
    assert DEFAULT_ISSUANCE_CAP == 3_355_443
    with pytest.raises(LocatorStoreError):
        code_hmac("AB3DE", b"short")


def test_lookup_tags_are_hmac_not_plaintext() -> None:
    tag = lookup_tag("email", "a42@example.test", bytes([0x0D] * 32))
    assert len(tag) == 32 and b"a42" not in tag
