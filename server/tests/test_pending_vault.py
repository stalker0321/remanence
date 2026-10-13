"""T185 vault tests: dev AEAD sealing, restart, KMS fail-closed, fake limits.

Synthetic data only. No database, no network, no real KMS.
"""

from __future__ import annotations

import uuid

import pytest
import tink
from tink import tink_config

from remanence.pending import domains
from remanence.pending.vault import (
    DevSealedVault,
    FakeHandleVault,
    ProductionKmsVault,
    VaultError,
    build_escrow_aad,
    kms_context_for,
    validate_escrow_plaintext,
)
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

CAPSULE = uuid.UUID("11111111-2222-3333-4444-555555555555").bytes
TARGET = uuid.UUID("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").bytes
SENDER = uuid.UUID("01010101-0202-0303-0404-050505050505").bytes
H = bytes([0x11] * 32)
D = bytes([0x22] * 32)
KC = bytes([0x33] * 32)
EXPIRES = 1798765432
DEV_KEY = bytes([0x07] * 32)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _payload() -> dv2.PendingEscrowPlaintextV1:
    return dv2.PendingEscrowPlaintextV1(
        escrow_version=1,
        capsule_protocol_version=2,
        capsule_id=CAPSULE,
        delivery_target_id=TARGET,
        sender_user_id=SENDER,
        publish_statement_sha256=H,
        address_commitment=D,
        key_commitment=KC,
        claim_expires_at_epoch_seconds=EXPIRES,
        capsule_aead_keyset=b"synthetic-K-bytes",
        address_opening=dv2.AddressOpening(
            normalization_version=1,
            channel=1,
            normalized_destination="a42@example.test",
            salt=bytes(range(32)),
        ),
    )


def _aad() -> dv2.PendingEscrowAadV1:
    return build_escrow_aad(
        capsule_id=CAPSULE,
        delivery_target_id=TARGET,
        sender_user_id=SENDER,
        publish_statement_sha256=H,
        address_commitment=D,
        key_commitment=KC,
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=EXPIRES,
    )


def test_dev_sealed_roundtrip_and_restart() -> None:
    vault = DevSealedVault(mode="test", key_id="synthetic-dev-1", key_32=DEV_KEY)
    handle = vault.seal(_payload(), _aad())
    assert handle.vault == "dev-aead-v1"
    assert handle.ciphertext != _payload().SerializeToString()
    # Restart: fresh instance, same supplied config key, opens persisted handle.
    reopened = DevSealedVault(mode="test", key_id="synthetic-dev-1", key_32=DEV_KEY)
    out = reopened.open(handle, _aad())
    assert out.capsule_aead_keyset == b"synthetic-K-bytes"
    assert out.address_opening.normalized_destination == "a42@example.test"


def test_dev_sealed_wrong_aad_and_swapped_ciphertext_reject() -> None:
    vault = DevSealedVault(mode="dev", key_id="synthetic-dev-1", key_32=DEV_KEY)
    handle = vault.seal(_payload(), _aad())
    other = build_escrow_aad(
        capsule_id=CAPSULE,
        delivery_target_id=TARGET,
        sender_user_id=SENDER,
        publish_statement_sha256=H,
        address_commitment=bytes([0x99] * 32),
        key_commitment=KC,
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=EXPIRES,
    )
    with pytest.raises(VaultError):
        vault.open(handle, other)
    handle2 = vault.seal(_payload(), other)
    with pytest.raises(VaultError):
        vault.open(handle2, _aad())  # binding rebuilt from verified S, not stored AAD


def test_dev_and_fake_vaults_refuse_prod_beta() -> None:
    with pytest.raises(VaultError):
        DevSealedVault(mode="prod", key_id="x", key_32=DEV_KEY)
    with pytest.raises(VaultError):
        DevSealedVault(mode="beta", key_id="x", key_32=DEV_KEY)
    with pytest.raises(VaultError):
        FakeHandleVault(mode="prod")
    with pytest.raises(VaultError):
        FakeHandleVault(mode="beta")


def test_production_kms_fail_closed_without_custody() -> None:
    with pytest.raises(VaultError):
        ProductionKmsVault(kms_client=None, kms_key_id="any", allowed_key_ids=("any",))
    with pytest.raises(VaultError):
        ProductionKmsVault(
            kms_client=object(), kms_key_id="evil", allowed_key_ids=("kms-allow-1",)
        )


def test_fake_handle_is_not_encryption_evidence() -> None:
    vault = FakeHandleVault(mode="test")
    handle = vault.seal(_payload(), _aad())
    assert handle.vault == "fake-handle-v1"  # marker, never "encryption"
    assert handle.ciphertext.startswith(b"FAKE-HANDLE-")
    assert vault.open(handle, _aad()).capsule_id == CAPSULE


def test_kms_context_carries_only_opaque_hashes() -> None:
    ctx = kms_context_for(_aad())
    assert ctx["purpose"] == "remanence-pending-v1"
    assert len(bytes.fromhex(ctx["binding"])) == 32
    assert "a42@example.test" not in ctx["binding"]
    validate_escrow_plaintext(_payload())
    bad = _payload()
    bad.channel if False else None
    bad.address_opening.channel = 9
    with pytest.raises(VaultError):
        validate_escrow_plaintext(bad)
    assert domains.CLAIM_BINDING_DOMAIN == "postmark/claim-binding/v1"
