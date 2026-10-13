"""T185 M2 isolated-PG tests: registry, routing, locator, profiles, migration.

Each test gets a uniquely named synthetic database (alembic head,
including 0013). Never touches t181_clone or live onboarding DBs.
Synthetic data only; no providers, no network beyond localhost PG.
"""

from __future__ import annotations

import hashlib
import io
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import tink
from sqlalchemy import inspect, select
from tink import signature, tink_config
from tink.proto import hpke_pb2, tink_pb2

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.models import Capsule, CapsuleState
from remanence.pending import locator as locator_pure
from remanence.pending.locator_store import (
    LocatorStoreError,
    activate,
    allocate_reserved,
    code_hmac,
    resolve_active,
    retire,
)
from remanence.pending.models import DeliveryAddressBinding
from remanence.pending.profiles import (
    ProfileError,
    register_receive,
    set_first_handle,
    summarize_user,
)
from remanence.pending.registry import (
    RegistryError,
    address_fate,
    advisory_key,
    live_verified,
    lock_address,
    lookup_tag,
    reserve,
    retire_binding,
    verify_binding,
    verify_evidence_mac,
)
from remanence.pending.routing import (
    RoutingError,
    check_finalize_route,
    decide_for_send,
    freeze_route,
    open_destination,
)
from remanence.users.key_bundle_validation import HPKE_PUBLIC_KEY_TYPE_URL
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

NOW = datetime(2030, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
HMAC_KEY = bytes([0x0D] * 32)
EVIDENCE_KEY = bytes([0x0E] * 32)
DEST_KEY = bytes([0x0F] * 32)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _hpke_public_keyset() -> tuple[bytes, bytes]:
    public = hpke_pb2.HpkePublicKey(
        version=0,
        params=hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        ),
        public_key=bytes(range(32)),
    )
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=tink_pb2.KeyData(
                type_url=HPKE_PUBLIC_KEY_TYPE_URL,
                value=public.SerializeToString(),
                key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
            ),
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    signing = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    buf = io.BytesIO()
    signing.public_keyset_handle().write_no_secret(tink.BinaryKeysetWriter(buf))
    return keyset.SerializeToString(), buf.getvalue()


def _make_user(session, *, handle: str | None) -> User:
    tag = uuid.uuid4().hex[:12]
    user = User(
        id=uuid.uuid4(),
        email_normalized=f"u-{tag}@example.test",
        handle_normalized=handle,
        handle_display=handle,
    )
    session.add(user)
    session.flush()
    return user


def _make_bundle(session, user: User) -> UserKeyBundle:
    enc, signing = _hpke_public_keyset()
    bundle = UserKeyBundle(
        id=uuid.uuid4(),
        user_id=user.id,
        encryption_public_keyset=enc,
        signing_public_keyset=signing,
        suite="HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocol_version=1,
        status=KeyBundleStatus.ACTIVE,
    )
    session.add(bundle)
    session.flush()
    return bundle


def _make_capsule(session, sender: User, sender_bundle, recipient: User, recipient_bundle) -> Capsule:
    capsule = Capsule(
        id=uuid.uuid4(),
        sender_user_id=sender.id,
        recipient_user_id=recipient.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=recipient_bundle.id,
        protocol_version=1,
        state=CapsuleState.DRAFT,
        draft_expires_at=NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    return capsule


def test_migration_0013_tables_exist(session_factory) -> None:
    with session_factory() as session:
        names = inspect(session.bind).get_table_names()
    for table in (
        "delivery_address_bindings",
        "pending_send_routes",
        "gift_locators",
        "used_locator_tombstones",
    ):
        assert table in names


def test_advisory_locks_survive_high_bit_digests(session_factory) -> None:
    with session_factory() as session:
        assert session.bind.dialect.name == "postgresql"
        negative = 0
        for i in range(200):
            normalized = f"lock-{i}@example.test"
            tag = lookup_tag("email", normalized, HMAC_KEY)
            key = advisory_key("email", tag)
            assert -(2**63) <= key <= 2**63 - 1
            if key < 0:
                negative += 1
            lock_address(session, "email", normalized, HMAC_KEY)
        assert negative > 0  # high-bit path actually exercised
        session.rollback()


def test_registry_lifecycle_and_owner_fence(session_factory) -> None:
    with session_factory() as session:
        owner = _make_user(session, handle="ownerhandle")
        other = _make_user(session, handle="otherhandle")
        session.commit()
        assert address_fate(session, kind="email", normalized="a42@example.test", hmac_key=HMAC_KEY) == "ABSENT"
        row = reserve(
            session, kind="email", normalized="a42@example.test", owner_user_id=owner.id, hmac_key=HMAC_KEY
        )
        assert row.state == "RESERVED"
        assert address_fate(session, kind="email", normalized="a42@example.test", hmac_key=HMAC_KEY) == "RESERVED"
        assert live_verified(session, kind="email", normalized="a42@example.test", hmac_key=HMAC_KEY) is None
        verified = verify_binding(
            session,
            kind="email",
            normalized="a42@example.test",
            owner_user_id=owner.id,
            evidence_method="otp",
            evidence_key=EVIDENCE_KEY,
            hmac_key=HMAC_KEY,
            now=NOW,
        )
        assert verified.state == "VERIFIED"
        assert verify_evidence_mac(verified, evidence_key=EVIDENCE_KEY) is True
        live = live_verified(session, kind="email", normalized="a42@example.test", hmac_key=HMAC_KEY)
        assert live is not None and live.owner_user_id == owner.id
        # Conflicting owner blocked, never auto-merged.
        with pytest.raises(RegistryError):
            verify_binding(
                session,
                kind="email",
                normalized="a42@example.test",
                owner_user_id=other.id,
                evidence_method="otp",
                evidence_key=EVIDENCE_KEY,
                hmac_key=HMAC_KEY,
                now=NOW,
            )
        # Foreign retire refused.
        with pytest.raises(RegistryError):
            retire_binding(session, binding_id=row.id, owner_user_id=other.id)
        retire_binding(session, binding_id=row.id, owner_user_id=owner.id)
        assert address_fate(session, kind="email", normalized="a42@example.test", hmac_key=HMAC_KEY) == "RETIRED"
        session.commit()


def test_backfill_reservations_never_routable(session_factory) -> None:
    with session_factory() as session:
        owner = _make_user(session, handle="backfillowner")
        session.commit()
        addresses = [f"historic-{i}@example.test" for i in range(5)]
        for address in addresses:
            row = reserve(
                session, kind="email", normalized=address, owner_user_id=owner.id, hmac_key=HMAC_KEY
            )
            assert row.state == "RESERVED"
        session.commit()
        for address in addresses:
            assert live_verified(session, kind="email", normalized=address, hmac_key=HMAC_KEY) is None
            assert (
                address_fate(session, kind="email", normalized=address, hmac_key=HMAC_KEY)
                == "RESERVED"
            )
        # Tampered evidence MAC detected (untrusted verified flag rejected).
        row = reserve(
            session, kind="phone", normalized="+380501234567", owner_user_id=owner.id, hmac_key=HMAC_KEY
        )
        verified = verify_binding(
            session,
            kind="phone",
            normalized="+380501234567",
            owner_user_id=owner.id,
            evidence_method="google",
            evidence_key=EVIDENCE_KEY,
            hmac_key=HMAC_KEY,
            now=NOW,
        )
        assert verify_evidence_mac(verified, evidence_key=EVIDENCE_KEY) is True
        verified.evidence_ref = bytes([0xFF] * 32)
        session.flush()
        assert verify_evidence_mac(verified, evidence_key=EVIDENCE_KEY) is False
        session.rollback()


def test_routing_freeze_both_orders_and_retired_errors(session_factory) -> None:
    with session_factory() as session:
        sender = _make_user(session, handle="senderone")
        recipient = _make_user(session, handle="recipientone")
        bundle = _make_bundle(session, recipient)
        session.commit()
        # Order 1: binding first -> v1, no escrow.
        verify_binding(
            session,
            kind="email",
            normalized="known@example.test",
            owner_user_id=recipient.id,
            evidence_method="otp",
            evidence_key=EVIDENCE_KEY,
            hmac_key=HMAC_KEY,
            now=NOW,
        )
        session.commit()
        decision = decide_for_send(
            session,
            destination_kind="email",
            normalized_destination="known@example.test",
            active_bundle_id=bundle.id,
            hmac_key=HMAC_KEY,
        )
        assert decision.mode == "EXISTING_USER_V1" and decision.user_id == recipient.id
        route = freeze_route(
            session,
            sender_user_id=sender.id,
            capsule_id=uuid.uuid4(),
            destination_kind="email",
            normalized_destination="known@example.test",
            destination_key_32=DEST_KEY,
            destination_key_mode="test",
            hmac_key=HMAC_KEY,
            decision=decision,
            now=NOW,
            pending_sends_enabled=True,
        )
        assert open_destination(sealed=bytes(route.sealed_destination), key_32=DEST_KEY) == "known@example.test"
        assert "normalized_destination" not in route.__table__.columns.keys()
        checked = check_finalize_route(
            session,
            route_id=route.id,
            sender_user_id=sender.id,
            capsule_id=route.capsule_id,
            mode="EXISTING_USER_V1",
            now=NOW,
        )
        assert checked.id == route.id
        # Order 2: freeze first (no binding) -> pending; late binding cannot rewrite.
        decision2 = decide_for_send(
            session,
            destination_kind="email",
            normalized_destination="late@example.test",
            active_bundle_id=None,
            hmac_key=HMAC_KEY,
        )
        assert decision2.mode == "PENDING_V2"
        route2 = freeze_route(
            session,
            sender_user_id=sender.id,
            capsule_id=uuid.uuid4(),
            destination_kind="email",
            normalized_destination="late@example.test",
            destination_key_32=DEST_KEY,
            destination_key_mode="test",
            hmac_key=HMAC_KEY,
            decision=decision2,
            now=NOW,
            pending_sends_enabled=True,
        )
        assert route2.delivery_target_id == decision2.delivery_target_id
        # Retired address: explicit error, not pending fallback.
        retired_row = reserve(
            session, kind="email", normalized="gone@example.test", owner_user_id=recipient.id, hmac_key=HMAC_KEY
        )
        retire_binding(session, binding_id=retired_row.id, owner_user_id=recipient.id)
        with pytest.raises(RoutingError):
            decide_for_send(
                session,
                destination_kind="email",
                normalized_destination="gone@example.test",
                active_bundle_id=None,
                hmac_key=HMAC_KEY,
            )
        session.commit()


def test_locator_allocate_activate_retire(session_factory) -> None:
    with session_factory() as session:
        sender = _make_user(session, handle="sendertwo")
        recipient = _make_user(session, handle="recipienttwo")
        sender_bundle = _make_bundle(session, sender)
        recipient_bundle = _make_bundle(session, recipient)
        capsule = _make_capsule(session, sender, sender_bundle, recipient, recipient_bundle)
        session.commit()
        row, code = allocate_reserved(
            session, capsule_id=capsule.id, hint_mask="a•••@•••", hmac_key=HMAC_KEY
        )
        assert locator_pure.is_canonical(code)
        assert row.state == "RESERVED"
        assert resolve_active(session, tag=code_hmac(code, HMAC_KEY)) is None
        activate(session, locator_id=row.id, code_ciphertext=b"enc-code")
        assert resolve_active(session, tag=code_hmac(code, HMAC_KEY)) is not None
        # Second capsule gets a different code; duplicate capsule refused.
        capsule2 = _make_capsule(session, sender, sender_bundle, recipient, recipient_bundle)
        _, code2 = allocate_reserved(
            session, capsule_id=capsule2.id, hint_mask="b•••@•••", hmac_key=HMAC_KEY
        )
        assert code2 != code
        with pytest.raises(LocatorStoreError):
            allocate_reserved(session, capsule_id=capsule.id, hint_mask="x", hmac_key=HMAC_KEY)
        # Retire open: lookup gone, tombstone kept, replay idempotent.
        retire(session, locator_id=row.id, terminal=False)
        assert resolve_active(session, tag=code_hmac(code, HMAC_KEY)) is None
        retire(session, locator_id=row.id, terminal=False)
        # Terminal path: activate then retire terminally.
        from remanence.pending.models import GiftLocator as _GL

        row2 = session.scalar(select(_GL).where(_GL.capsule_id == capsule2.id))
        assert row2 is not None
        activate(session, locator_id=row2.id, code_ciphertext=b"enc2")
        retire(session, locator_id=row2.id, terminal=True)
        assert resolve_active(session, tag=code_hmac(code2, HMAC_KEY)) is None
        # Capacity cap refuses issuance without impairing existing access.
        with pytest.raises(LocatorStoreError):
            allocate_reserved(
                session,
                capsule_id=uuid.uuid4(),
                hint_mask="x",
                hmac_key=HMAC_KEY,
                issued_count=3_355_443,
            )
        session.commit()


def test_locator_collision_preserves_outer_tx(session_factory, monkeypatch) -> None:
    """Forced UNIQUE collision rolls back to a savepoint only: the
    caller's uncommitted outer work (users/drafts) survives."""
    import remanence.pending.locator_store as store

    with session_factory() as session:
        sender = _make_user(session, handle="collidesender")
        recipient = _make_user(session, handle="colliderecipient")
        sender_bundle = _make_bundle(session, sender)
        recipient_bundle = _make_bundle(session, recipient)
        capsule1 = _make_capsule(session, sender, sender_bundle, recipient, recipient_bundle)
        capsule2 = _make_capsule(session, sender, sender_bundle, recipient, recipient_bundle)
        _, code1 = allocate_reserved(
            session, capsule_id=capsule1.id, hint_mask="c•••@•••", hmac_key=HMAC_KEY
        )
        real_gen = store.generate_candidate
        calls = {"n": 0}

        def flaky() -> str:
            calls["n"] += 1
            return code1 if calls["n"] == 1 else real_gen()

        monkeypatch.setattr(store, "generate_candidate", flaky)
        # Blind the pre-check once so the collision reaches the UNIQUE
        # constraint (the real concurrent-race path).
        real_known = store._code_known
        seen = {"n": 0}

        def blind_once(sess, tag) -> bool:
            seen["n"] += 1
            return False if seen["n"] == 1 else real_known(sess, tag)

        monkeypatch.setattr(store, "_code_known", blind_once)
        _, code2 = allocate_reserved(
            session, capsule_id=capsule2.id, hint_mask="d•••@•••", hmac_key=HMAC_KEY
        )
        assert code2 != code1 and calls["n"] >= 2
        # Outer uncommitted work survived (a full rollback would have lost
        # the users and broken this commit).
        session.commit()
        assert session.get(User, sender.id) is not None
        assert session.get(User, recipient.id) is not None


def test_profiles_receive_and_first_handle(session_factory) -> None:
    from remanence.auth.google_models import GoogleFlow, GoogleIdentity

    with session_factory() as session:
        enc, signing = _hpke_public_keyset()
        ticket = GoogleFlow(
            id=uuid.uuid4(),
            purpose="REGISTER",
            secret_hash=hashlib.sha256(b"ticket-secret").digest(),
            subject="google-subject-1",
            email="receive@example.test",
            expires_at=NOW + timedelta(minutes=10),
            consumed_at=None,
        )
        session.add(ticket)
        session.flush()
        result = register_receive(
            session,
            ticket=ticket,
            key_bundle_id=uuid.uuid4(),
            suite="HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            protocol_version=1,
            encryption_public_keyset=enc,
            signing_public_keyset=signing,
            now=NOW,
            pending_protocol_enabled=True,
        )
        assert result.handle is None
        assert "access_token" not in repr(result) and "refresh_token" not in repr(result)
        assert ticket.consumed_at == NOW
        assert session.get(GoogleIdentity, "google-subject-1") is not None
        session.commit()
        # Consumed ticket cannot mint a second account/session.
        with pytest.raises(ProfileError):
            register_receive(
                session,
                ticket=ticket,
                key_bundle_id=uuid.uuid4(),
                suite="HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
                protocol_version=1,
                encryption_public_keyset=enc,
                signing_public_keyset=signing,
                now=NOW,
                pending_protocol_enabled=True,
            )
        session.rollback()
        user = session.get(User, result.user_id)
        assert user.handle_normalized is None and user.handle_display is None
        # Old client (v1 capability) gets upgrade-required before any session.
        with pytest.raises(ProfileError) as caught:
            summarize_user(user, client_profile_version=1)
        assert caught.value.code == "CLIENT_UPGRADE_REQUIRED"
        summary = summarize_user(user, client_profile_version=2)
        assert summary["handle"] is None
        # One-time handle setup, replay, immutability.
        updated = set_first_handle(session, user_id=user.id, handle="NewHandle")
        assert updated.handle_normalized == "newhandle"
        again = set_first_handle(session, user_id=user.id, handle="newhandle")
        assert again.id == user.id
        with pytest.raises(ProfileError) as caught2:
            set_first_handle(session, user_id=user.id, handle="different")
        assert caught2.value.code == "HANDLE_IMMUTABLE"
        session.commit()


def test_first_handle_concurrent_assignments_one_winner(session_factory) -> None:
    """Two different concurrent first assignments must not both succeed:
    the row lock serializes them; the loser sees the winner's handle."""
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    with session_factory() as session:
        user = _make_user(session, handle=None)
        session.commit()
        user_id = user.id
    barrier = Barrier(2)
    outcomes: dict[str, str] = {}

    def attempt(name: str, handle: str) -> None:
        with session_factory() as session:
            barrier.wait(timeout=30)
            try:
                set_first_handle(session, user_id=user_id, handle=handle)
                session.commit()
                outcomes[name] = "assigned"
            except ProfileError as exc:
                session.rollback()
                outcomes[name] = exc.code

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda args: attempt(*args), [("a", "alphaone"), ("b", "betatwo")]))
    assert sorted(outcomes.values()) == ["HANDLE_IMMUTABLE", "assigned"]
    with session_factory() as session:
        final_handle = session.get(User, user_id).handle_normalized
        assert final_handle in ("alphaone", "betatwo")


def test_downgrade_refuses_with_handleless_accounts(session_factory) -> None:
    from alembic import command
    from alembic.config import Config

    from test_session_repository_create import _ALEMBIC_INI

    with session_factory() as session:
        _make_user(session, handle=None)
        session.commit()
    config = Config(str(_ALEMBIC_INI))
    config.set_main_option("path_separator", "os")
    with pytest.raises(RuntimeError):
        command.downgrade(config, "0012_anonymous_telemetry")


def test_backfill_bootstrap_reserved_only(session_factory) -> None:
    from remanence.pending.registry import backfill_reservations

    with session_factory() as session:
        owner_a = _make_user(session, handle="backfilltwo")
        owner_b = _make_user(session, handle="backfillthree")
        session.commit()
        rows = [
            ("email", "historic-a@example.test", owner_a.id),
            ("email", "historic-b@example.test", owner_b.id),
            ("phone", "+380501111111", owner_a.id),
        ]
        outcome = backfill_reservations(session, rows=rows, hmac_key=HMAC_KEY)
        assert outcome == {"created": 3, "existing": 0, "blocked": 0}
        session.commit()
        # None routable; rerun is idempotent; conflicts never merge.
        for kind, normalized, _ in rows:
            assert live_verified(session, kind=kind, normalized=normalized, hmac_key=HMAC_KEY) is None
            assert address_fate(session, kind=kind, normalized=normalized, hmac_key=HMAC_KEY) == "RESERVED"
        again = backfill_reservations(session, rows=rows, hmac_key=HMAC_KEY)
        assert again == {"created": 0, "existing": 3, "blocked": 0}
        conflict = backfill_reservations(
            session,
            rows=[("email", "historic-a@example.test", owner_b.id)],
            hmac_key=HMAC_KEY,
        )
        assert conflict == {"created": 0, "existing": 0, "blocked": 1}
        session.rollback()


def test_retired_alias_never_rebound(session_factory) -> None:
    from remanence.pending.registry import RegistryError

    with session_factory() as session:
        owner = _make_user(session, handle="retiredowner")
        other = _make_user(session, handle="retiredother")
        session.commit()
        row = reserve(
            session, kind="email", normalized="dead@example.test",
            owner_user_id=owner.id, hmac_key=HMAC_KEY,
        )
        verify_binding(
            session, kind="email", normalized="dead@example.test",
            owner_user_id=owner.id, evidence_method="otp",
            evidence_key=EVIDENCE_KEY, hmac_key=HMAC_KEY, now=NOW,
        )
        retire_binding(session, binding_id=row.id, owner_user_id=owner.id)
        session.commit()
        assert address_fate(session, kind="email", normalized="dead@example.test", hmac_key=HMAC_KEY) == "RETIRED"
        # No automatic rebind: same owner, other owner, all refused.
        with pytest.raises(RegistryError):
            reserve(
                session, kind="email", normalized="dead@example.test",
                owner_user_id=owner.id, hmac_key=HMAC_KEY,
            )
        with pytest.raises(RegistryError):
            reserve(
                session, kind="email", normalized="dead@example.test",
                owner_user_id=other.id, hmac_key=HMAC_KEY,
            )
        with pytest.raises(RegistryError):
            verify_binding(
                session, kind="email", normalized="dead@example.test",
                owner_user_id=owner.id, evidence_method="otp",
                evidence_key=EVIDENCE_KEY, hmac_key=HMAC_KEY, now=NOW,
            )
        session.rollback()
