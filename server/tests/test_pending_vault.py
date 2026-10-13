"""T185 vault tests: strict K parser, binding cross-checks, dev sealing.

Synthetic data only. No database, no network, no real KMS.
"""

from __future__ import annotations

import io
import uuid

import pytest
import tink
from tink import hybrid, tink_config
from tink.proto import tink_pb2

from remanence.pending import domains
from remanence.pending.vault import (
    DevSealedVault,
    FakeHandleVault,
    ProductionKmsVault,
    VaultError,
    build_escrow_aad,
    kms_context_for,
    parse_exact_capsule_key,
    validate_escrow_plaintext,
    verify_escrow_bindings,
)
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

CAPSULE = uuid.UUID("11111111-2222-3333-4444-555555555555").bytes
TARGET = uuid.UUID("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").bytes
SENDER = uuid.UUID("01010101-0202-0303-0404-050505050505").bytes
H = bytes([0x11] * 32)
EXPIRES = 1798765432
DEV_KEY = bytes([0x07] * 32)
KEY_A = bytes([0x0A] * 32)
KEY_B = bytes([0x0B] * 32)

_AES_URL = "type.googleapis.com/google.crypto.tink.AesGcmKey"
_HPKE_PRIV_URL = "type.googleapis.com/google.crypto.tink.HpkePrivateKey"
_ED_PRIV_URL = "type.googleapis.com/google.crypto.tink.Ed25519PrivateKey"


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _aes_keyset_bytes(key_value: bytes) -> bytes:
    from tink.proto import aes_gcm_pb2

    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=tink_pb2.KeyData(
                type_url=_AES_URL,
                value=aes_gcm_pb2.AesGcmKey(version=0, key_value=key_value).SerializeToString(),
                key_material_type=tink_pb2.KeyData.SYMMETRIC,
            ),
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset.SerializeToString()


def _typed_keyset_bytes(type_url: str, material: int, value: bytes = b"\x00\x01\x02") -> bytes:
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=tink_pb2.KeyData(
                type_url=type_url,
                value=value,
                key_material_type=material,
            ),
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset.SerializeToString()


def _opening(destination: str = "a42@example.test"):
    return dv2.AddressOpening(
        normalization_version=1,
        channel=1,
        normalized_destination=destination,
        salt=bytes(range(32)),
    )


def _commitments(key_bytes: bytes, opening):
    d = domains.domain_digest(
        domains.DELIVERY_ADDRESS_DOMAIN, opening.SerializeToString(deterministic=True)
    )
    kc_input = dv2.KeyCommitmentInput(
        version=1, capsule_id=CAPSULE, delivery_target_id=TARGET, capsule_aead_keyset=key_bytes
    )
    kc = domains.domain_digest(
        domains.DELIVERY_KEY_DOMAIN, kc_input.SerializeToString(deterministic=True)
    )
    return d, kc


def _payload(key_bytes: bytes | None = None, destination: str = "a42@example.test"):
    key_bytes = _aes_keyset_bytes(KEY_A) if key_bytes is None else key_bytes
    opening = _opening(destination)
    d, kc = _commitments(key_bytes, opening)
    return dv2.PendingEscrowPlaintextV1(
        escrow_version=1,
        capsule_protocol_version=2,
        capsule_id=CAPSULE,
        delivery_target_id=TARGET,
        sender_user_id=SENDER,
        publish_statement_sha256=H,
        address_commitment=d,
        key_commitment=kc,
        claim_expires_at_epoch_seconds=EXPIRES,
        capsule_aead_keyset=key_bytes,
        address_opening=opening,
    )


def _aad(payload=None):
    payload = _payload() if payload is None else payload
    return build_escrow_aad(
        capsule_id=bytes(payload.capsule_id),
        delivery_target_id=bytes(payload.delivery_target_id),
        sender_user_id=bytes(payload.sender_user_id),
        publish_statement_sha256=bytes(payload.publish_statement_sha256),
        address_commitment=bytes(payload.address_commitment),
        key_commitment=bytes(payload.key_commitment),
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=payload.claim_expires_at_epoch_seconds,
    )


# --- strict K parser ------------------------------------------------------------


def test_exact_key_parser_accepts_single_aes_and_rejects_others() -> None:
    assert parse_exact_capsule_key(_aes_keyset_bytes(KEY_A)) == KEY_A
    # HPKE / Ed25519 private keysets rejected by type, even well-formed.
    with pytest.raises(VaultError):
        parse_exact_capsule_key(
            _typed_keyset_bytes(_HPKE_PRIV_URL, tink_pb2.KeyData.ASYMMETRIC_PRIVATE)
        )
    with pytest.raises(VaultError):
        parse_exact_capsule_key(
            _typed_keyset_bytes(_ED_PRIV_URL, tink_pb2.KeyData.ASYMMETRIC_PRIVATE)
        )
    # Real well-formed non-AES keyset (public HPKE) still rejected by type.
    priv = tink.KeysetHandle.generate_new(
        hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM
    )
    buf = io.BytesIO()
    priv.public_keyset_handle().write_no_secret(tink.BinaryKeysetWriter(buf))
    with pytest.raises(VaultError):
        parse_exact_capsule_key(buf.getvalue())
    # ARK-shaped / opaque garbage rejected.
    with pytest.raises(VaultError):
        parse_exact_capsule_key(bytes(range(32)))
    with pytest.raises(VaultError):
        parse_exact_capsule_key(b"")
    # Multiple keys rejected.
    from tink.proto import aes_gcm_pb2

    multi = tink_pb2.Keyset(primary_key_id=1)
    for key_id in (1, 2):
        multi.key.append(
            tink_pb2.Keyset.Key(
                key_data=tink_pb2.KeyData(
                    type_url=_AES_URL,
                    value=aes_gcm_pb2.AesGcmKey(version=0, key_value=KEY_A).SerializeToString(),
                    key_material_type=tink_pb2.KeyData.SYMMETRIC,
                ),
                status=tink_pb2.ENABLED,
                key_id=key_id,
                output_prefix_type=tink_pb2.TINK,
            )
        )
    with pytest.raises(VaultError):
        parse_exact_capsule_key(multi.SerializeToString())
    # Disabled key rejected.
    disabled = tink_pb2.Keyset(primary_key_id=1)
    disabled.key.append(
        tink_pb2.Keyset.Key(
            key_data=tink_pb2.KeyData(
                type_url=_AES_URL,
                value=aes_gcm_pb2.AesGcmKey(version=0, key_value=KEY_A).SerializeToString(),
                key_material_type=tink_pb2.KeyData.SYMMETRIC,
            ),
            status=tink_pb2.DISABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    with pytest.raises(VaultError):
        parse_exact_capsule_key(disabled.SerializeToString())
    # Trailing data rejected.
    with pytest.raises(VaultError):
        parse_exact_capsule_key(_aes_keyset_bytes(KEY_A) + b"\xff\xff")


# --- binding cross-checks ---------------------------------------------------------


def test_bindings_recomputed_and_cross_checked() -> None:
    payload, aad = _payload(), None
    aad = _aad(payload)
    verify_escrow_bindings(payload, aad)  # consistent triple passes
    # Stale D (opening changed after commitment) rejected.
    stale = _payload(destination="other@example.test")
    stale.address_commitment = payload.address_commitment
    with pytest.raises(VaultError):
        verify_escrow_bindings(stale, _aad(payload))
    # Stale KC (K swapped, commitment not recomputed) rejected.
    swapped = _payload(key_bytes=_aes_keyset_bytes(KEY_B))
    swapped.key_commitment = payload.key_commitment
    with pytest.raises(VaultError):
        verify_escrow_bindings(swapped, _aad(payload))
    # Payload sealed under mismatched AAD rejected at seal time.
    vault = DevSealedVault(mode="test", key_id="synthetic-dev-1", key_32=DEV_KEY)
    other_aad = build_escrow_aad(
        capsule_id=CAPSULE,
        delivery_target_id=TARGET,
        sender_user_id=SENDER,
        publish_statement_sha256=H,
        address_commitment=bytes(payload.address_commitment),
        key_commitment=bytes([0x99] * 32),
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=EXPIRES,
    )
    with pytest.raises(VaultError):
        vault.seal(payload, other_aad)
    # Expiry drift between payload and AAD rejected.
    drifted = _aad(payload)
    drifted.claim_expires_at_epoch_seconds += 1
    with pytest.raises(VaultError):
        verify_escrow_bindings(payload, drifted)


# --- dev sealed vault ------------------------------------------------------------


def test_dev_sealed_roundtrip_and_restart() -> None:
    vault = DevSealedVault(mode="test", key_id="synthetic-dev-1", key_32=DEV_KEY)
    payload, aad = _payload(), None
    aad = _aad(payload)
    handle = vault.seal(payload, aad)
    assert handle.vault == "dev-aead-v1"
    assert handle.ciphertext != payload.SerializeToString()
    reopened = DevSealedVault(mode="test", key_id="synthetic-dev-1", key_32=DEV_KEY)
    out = reopened.open(handle, aad)
    assert parse_exact_capsule_key(bytes(out.capsule_aead_keyset)) == KEY_A
    assert out.address_opening.normalized_destination == "a42@example.test"


def test_dev_sealed_wrong_aad_and_swapped_ciphertext_reject() -> None:
    vault = DevSealedVault(mode="dev", key_id="synthetic-dev-1", key_32=DEV_KEY)
    payload, aad = _payload(), None
    aad = _aad(payload)
    handle = vault.seal(payload, aad)
    other = build_escrow_aad(
        capsule_id=CAPSULE,
        delivery_target_id=TARGET,
        sender_user_id=SENDER,
        publish_statement_sha256=H,
        address_commitment=bytes([0x99] * 32),
        key_commitment=bytes(payload.key_commitment),
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=EXPIRES,
    )
    with pytest.raises(VaultError):
        vault.open(handle, other)
    payload2 = _payload(destination="b7@example.test")
    handle2 = vault.seal(payload2, _aad(payload2))
    with pytest.raises(VaultError):
        vault.open(handle2, aad)  # binding rebuilt from verified S, not stored AAD


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
    payload, aad = _payload(), None
    aad = _aad(payload)
    handle = vault.seal(payload, aad)
    assert handle.vault == "fake-handle-v1"  # marker, never "encryption"
    assert handle.ciphertext.startswith(b"FAKE-HANDLE-")
    assert vault.open(handle, aad).capsule_id == CAPSULE


def test_kms_context_carries_only_opaque_hashes() -> None:
    payload, aad = _payload(), None
    aad = _aad(payload)
    ctx = kms_context_for(aad)
    assert ctx["purpose"] == "remanence-pending-v1"
    assert len(bytes.fromhex(ctx["binding"])) == 32
    assert "a42@example.test" not in ctx["binding"]
    validate_escrow_plaintext(payload)
    bad = _payload()
    bad.address_opening.channel = 9
    with pytest.raises(VaultError):
        validate_escrow_plaintext(bad)
    assert domains.CLAIM_BINDING_DOMAIN == "postmark/claim-binding/v1"
