"""T185 M1: frozen v2 domains, locator, normalization, fixtures, real crypto.

Pure unit + real-Tink runtime tests. No database, no network, no providers.
Synthetic data only. v1 bytes untouched (guarded by git + existing suites).
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import uuid
from pathlib import Path

import pytest
import tink
from tink import aead, hybrid, signature, tink_config

from remanence.pending import domains
from remanence.pending.locator import (
    ALPHABET,
    CODE_LENGTH,
    LocatorError,
    allocate_unique,
    generate_candidate,
    is_canonical,
    normalize_code,
)
from remanence.pending.normalize import (
    NormalizationError,
    normalize_email_destination,
    normalize_phone_destination,
)
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

FIXDIR = Path(__file__).resolve().parents[2] / "protocol" / "fixtures"


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


# --- domains -----------------------------------------------------------------


def test_all_v2_domains_distinct_and_nul_framed() -> None:
    assert len(set(domains.ALL_V2_DOMAINS)) == len(domains.ALL_V2_DOMAINS) == 8
    for domain in domains.ALL_V2_DOMAINS:
        framed = domains.domain_prefix(domain)
        assert framed == domain.encode("ascii") + b"\x00"
    # v1 publish prefix is delimiter-free; none of the new domains collide.
    assert b"postmark/publish/v1" not in domains.ALL_V2_DOMAINS


def test_domain_digest_matches_manual_sha256() -> None:
    msg = b"\x08\x02test"
    assert domains.domain_digest("postmark/artifact/v2", msg) == hashlib.sha256(
        b"postmark/artifact/v2\x00" + msg
    ).digest()


# --- locator -----------------------------------------------------------------


def test_locator_normalization_matrix() -> None:
    assert normalize_code("ab3de") == "AB3DE"
    assert normalize_code("ab 3-de") == "AB3DE"
    assert normalize_code("ABOIL") == "AB011"  # O->0, I/L->1 on input
    assert is_canonical("AB3DE")
    assert not is_canonical("AB011") is False  # canonical alphabet validity
    with pytest.raises(LocatorError):
        normalize_code("ABCD")  # 4 chars rejected, never prefix-matched
    with pytest.raises(LocatorError):
        normalize_code("ABCD12345678")  # 12 chars rejected
    with pytest.raises(LocatorError):
        normalize_code("AB*DE")
    with pytest.raises(LocatorError):
        normalize_code("АБ3ДЕ")  # non-ASCII rejected


def test_locator_alphabet_capacity() -> None:
    assert len(ALPHABET) == 32 and CODE_LENGTH == 5
    assert 32**5 == 33554432
    for _ in range(25):
        assert is_canonical(generate_candidate())


def test_locator_allocate_respects_tombstones_and_busy() -> None:
    used = {"AAAAA"}

    def exists(code: str) -> bool:
        return code in used

    got = allocate_unique(exists, attempts=5)
    assert is_canonical(got) and got not in used
    with pytest.raises(LocatorError):
        allocate_unique(lambda code: True, attempts=3)  # exhausted -> BUSY


# --- normalization ------------------------------------------------------------


def test_email_normalization_v1() -> None:
    assert normalize_email_destination("  A42@Example.Test ") == "a42@example.test"
    # No Gmail dot/plus aliasing.
    assert normalize_email_destination("a.b+tag@example.test") == "a.b+tag@example.test"
    with pytest.raises(NormalizationError):
        normalize_email_destination("not-an-email")
    with pytest.raises(NormalizationError):
        normalize_email_destination("a" * 250 + "@example.test")  # >254 bytes


def test_phone_normalization_requires_explicit_e164() -> None:
    assert normalize_phone_destination("+380501234567") == "+380501234567"
    with pytest.raises(NormalizationError):
        normalize_phone_destination("0501234567")  # no inferred country
    with pytest.raises(NormalizationError):
        normalize_phone_destination("+380 50 123 4567 ext 9")


# --- canonical fixture parity --------------------------------------------------


def _load(name: str) -> dict:
    return json.loads((FIXDIR / name).read_text())


def test_canonical_fixture_byte_parity() -> None:
    fx = _load("delivery-v2-canonical.json")
    opening = dv2.AddressOpening(
        normalization_version=1,
        channel=1,
        normalized_destination="a42@example.test",
        salt=bytes(range(32)),
    )
    assert opening.SerializeToString().hex() == fx["address_opening_hex"]
    assert (
        domains.domain_digest(domains.DELIVERY_ADDRESS_DOMAIN, opening.SerializeToString()).hex()
        == fx["address_commitment_d_hex"]
    )
    kc_input = dv2.KeyCommitmentInput(
        version=1,
        capsule_id=uuid.UUID(fx["capsule_id"]).bytes,
        delivery_target_id=uuid.UUID(fx["delivery_target_id"]).bytes,
        capsule_aead_keyset=bytes.fromhex(fx["test_vector_key_hex"]),
    )
    assert kc_input.SerializeToString().hex() == fx["key_commitment_input_hex"]
    assert (
        domains.domain_digest(domains.DELIVERY_KEY_DOMAIN, kc_input.SerializeToString()).hex()
        == fx["key_commitment_kc_hex"]
    )
    assert (
        hashlib.sha256(bytes.fromhex(fx["pending_publish_statement_s_hex"])).hexdigest()
        == fx["publish_statement_sha256_h_hex"]
    )
    assert (
        hashlib.sha256(bytes.fromhex(fx["claim_binding_r_hex"])).hexdigest()
        == fx["claim_binding_sha256_hex"]
    )
    hpke_info = bytes.fromhex(fx["claimed_envelope_hpke_info_hex"])
    assert hpke_info == (
        domains.domain_prefix(domains.CLAIMED_ENVELOPE_DOMAIN)
        + bytes.fromhex(fx["claimed_envelope_context_hex"])
    )


def test_escrow_auxiliary_fields_frozen_and_parity() -> None:
    """Root review: exact auxiliary escrow canonical fields frozen (P1)."""
    from remanence.pending.vault import build_escrow_aad, kms_context_for

    fx = _load("delivery-v2-canonical.json")
    capsule = uuid.UUID(fx["capsule_id"]).bytes
    target = uuid.UUID(fx["delivery_target_id"]).bytes
    sender = uuid.UUID(fx["sender_user_id"]).bytes
    h = bytes.fromhex(fx["publish_statement_sha256_h_hex"])
    d = bytes.fromhex(fx["address_commitment_d_hex"])
    kc = bytes.fromhex(fx["key_commitment_kc_hex"])
    aad = build_escrow_aad(
        capsule_id=capsule,
        delivery_target_id=target,
        sender_user_id=sender,
        publish_statement_sha256=h,
        address_commitment=d,
        key_commitment=kc,
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=1798765432 + 180 * 86400,
    )
    assert aad.SerializeToString(deterministic=True).hex() == fx["escrow_aad_canonical_hex"]
    assert kms_context_for(aad) == fx["escrow_kms_context"]
    payload = dv2.PendingEscrowPlaintextV1.FromString(
        bytes.fromhex(fx["escrow_payload_canonical_hex"])
    )
    assert payload.escrow_version == 1
    assert payload.capsule_protocol_version == 2
    assert payload.capsule_id == capsule
    assert payload.delivery_target_id == target
    assert payload.sender_user_id == sender
    assert payload.publish_statement_sha256 == h
    assert payload.address_commitment == d
    assert payload.key_commitment == kc
    assert payload.address_opening.normalized_destination == "a42@example.test"
    assert len(payload.address_opening.salt) == 32


# --- real-Tink vectors ----------------------------------------------------------


def test_sender_ed25519_verify_and_wrong_domain_reject() -> None:
    fx = _load("delivery-v2-crypto.json")
    pub_bin = base64.b64decode(fx["sender_ed25519_public_keyset_b64"])
    sig = bytes.fromhex(fx["pending_publish_signature_69_hex"])
    assert len(sig) == 69  # TINK framing preserved
    good_input = bytes.fromhex(fx["pending_publish_domain_input_hex"])
    handle = tink.KeysetHandle.read_no_secret(tink.BinaryKeysetReader(pub_bin))
    verifier = handle.primitive(signature.PublicKeyVerify)
    verifier.verify(sig, good_input)  # does not raise
    # Wrong domain: publish signature presented as claim-intent -> reject.
    evil = domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + good_input[
        len(domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN)) :
    ]
    with pytest.raises(tink.TinkError):
        verifier.verify(sig, evil)
    # Tampered statement bytes -> reject.
    with pytest.raises(tink.TinkError):
        verifier.verify(sig, good_input[:-1] + b"\x00")


def test_aead_roundtrip_and_wrong_aad_reject() -> None:
    fx = _load("delivery-v2-crypto.json")
    aad = bytes.fromhex(fx["aead_aad_hex"])
    keyset = tink.KeysetHandle.generate_new(aead.aead_key_templates.AES256_GCM)
    prim = keyset.primitive(aead.Aead)
    plaintext = b"pending-artifact-plaintext-vector-01"
    ciphertext = prim.encrypt(plaintext, aad)
    assert prim.decrypt(ciphertext, aad) == plaintext
    assert ciphertext != plaintext
    with pytest.raises(tink.TinkError):
        prim.decrypt(ciphertext, b"postmark/artifact/v1\x00wrong-context")


def test_hpke_roundtrip_with_pinned_context_and_wrong_context_reject() -> None:
    fx = _load("delivery-v2-canonical.json")
    info = bytes.fromhex(fx["claimed_envelope_hpke_info_hex"])
    priv = tink.KeysetHandle.generate_new(
        hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM
    )
    pub_bin = io.BytesIO()
    priv.public_keyset_handle().write_no_secret(tink.BinaryKeysetWriter(pub_bin))
    pub = tink.KeysetHandle.read_no_secret(tink.BinaryKeysetReader(pub_bin.getvalue()))
    enc = pub.primitive(hybrid.HybridEncrypt)
    dec = priv.primitive(hybrid.HybridDecrypt)
    secret = b"claimed-envelope-plaintext-vector-01"
    sealed = enc.encrypt(secret, info)
    assert dec.decrypt(sealed, info) == secret
    with pytest.raises(tink.TinkError):
        dec.decrypt(sealed, b"postmark/claimed-envelope/v1\x00wrong")
