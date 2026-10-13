"""Pending-escrow vault adapters (T185). Fail-closed, no plaintext logging.

Boundaries (contract: tasks/T185/crypto-contract.md §3):
- Production requires configured independent KMS custody and fails closed
  without it. No dev key, fake handle, or local-file key is ever accepted
  in prod/beta.
- Dev/test may use DevSealedVault: real AEAD (Tink AES256_GCM) sealing
  with a synthetic caller-supplied config key. Dev-only, explicitly NOT
  production-equivalent (same-host key does not survive host compromise).
  Used for restart/persistence tests.
- FakeHandleVault returns opaque test handles with NO encryption. It is
  allowed ONLY in model-only unit tests and must NEVER be called
  encryption evidence.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from remanence.pending import domains
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

KMS_CONTEXT_PURPOSE = "remanence-pending-v1"
ESCROW_AAD_PURPOSE = "PENDING_CAPSULE_KEY"
MAX_ESCROW_PLAINTEXT_BYTES = 2048

_AES_GCM_KEY_TYPE_URL = "type.googleapis.com/google.crypto.tink.AesGcmKey"


class VaultError(RuntimeError):
    """Fail-closed vault failure. Never carries key/plaintext material."""


def parse_exact_capsule_key(key_bytes: bytes) -> bytes:
    """Parse K as exactly one ENABLED AES256_GCM TINK key; return raw key_value.

    Contract (crypto-contract §3): the exact-K parser accepts ONLY the
    single enabled symmetric keyset. HPKE/Ed25519/ARK-shaped bytes,
    multi-key keysets, disabled keys, malformed inner keys, non-canonical
    encodings and trailing data are rejected without echo/logging.
    """
    from tink.proto import aes_gcm_pb2, tink_pb2

    if not key_bytes or len(key_bytes) > MAX_ESCROW_PLAINTEXT_BYTES:
        raise VaultError("vault key malformed")
    try:
        keyset = tink_pb2.Keyset.FromString(key_bytes)
    except Exception as exc:
        raise VaultError("vault key malformed") from exc
    if keyset.SerializeToString() != key_bytes:
        raise VaultError("vault key non-canonical")
    if len(keyset.key) != 1:
        raise VaultError("vault key must be exactly one key")
    key = keyset.key[0]
    if key.status != tink_pb2.ENABLED:
        raise VaultError("vault key not enabled")
    if key.output_prefix_type != tink_pb2.TINK:
        raise VaultError("vault key prefix unsupported")
    if (
        key.key_data.key_material_type != tink_pb2.KeyData.SYMMETRIC
        or key.key_data.type_url != _AES_GCM_KEY_TYPE_URL
    ):
        raise VaultError("vault key type rejected")
    try:
        aes_key = aes_gcm_pb2.AesGcmKey.FromString(key.key_data.value)
    except Exception as exc:
        raise VaultError("vault key malformed") from exc
    if aes_key.version != 0 or len(aes_key.key_value) != 32:
        raise VaultError("vault key malformed")
    if aes_key.SerializeToString() != key.key_data.value:
        raise VaultError("vault key non-canonical")
    return aes_key.key_value


def escrow_aad_bytes(aad: dv2.PendingEscrowAadV1) -> bytes:
    """Canonical AAD bytes for seal/open binding."""
    return aad.SerializeToString(deterministic=True)


def kms_context_for(aad: dv2.PendingEscrowAadV1) -> dict[str, str]:
    """Exact KMS EncryptionContext: opaque hashes only, never PII/keys."""
    binding = hashlib.sha256(escrow_aad_bytes(aad)).hexdigest()
    return {"purpose": KMS_CONTEXT_PURPOSE, "binding": binding}


def build_escrow_aad(
    *,
    capsule_id: bytes,
    delivery_target_id: bytes,
    sender_user_id: bytes,
    publish_statement_sha256: bytes,
    address_commitment: bytes,
    key_commitment: bytes,
    claim_policy_version: int,
    claim_expires_at_epoch_seconds: int,
) -> dv2.PendingEscrowAadV1:
    """Rebuild AAD from verified S fields. Callers must pass S-derived values."""
    for name, value in (
        ("capsule_id", capsule_id),
        ("delivery_target_id", delivery_target_id),
        ("sender_user_id", sender_user_id),
    ):
        if len(value) != 16:
            raise VaultError("vault binding identifier malformed")
    for name, value in (
        ("publish_statement_sha256", publish_statement_sha256),
        ("address_commitment", address_commitment),
        ("key_commitment", key_commitment),
    ):
        if len(value) != 32:
            raise VaultError("vault binding digest malformed")
    return dv2.PendingEscrowAadV1(
        aad_version=1,
        purpose=ESCROW_AAD_PURPOSE,
        capsule_protocol_version=2,
        capsule_id=capsule_id,
        delivery_target_id=delivery_target_id,
        sender_user_id=sender_user_id,
        publish_statement_sha256=publish_statement_sha256,
        address_commitment=address_commitment,
        key_commitment=key_commitment,
        claim_policy_version=claim_policy_version,
        claim_expires_at_epoch_seconds=claim_expires_at_epoch_seconds,
    )


def validate_escrow_plaintext(payload: dv2.PendingEscrowPlaintextV1) -> None:
    """Strict parser: exact versions, sizes, single AES keyset. No echo."""
    if payload.escrow_version != 1 or payload.capsule_protocol_version != 2:
        raise VaultError("vault payload version unsupported")
    for value in (payload.capsule_id, payload.delivery_target_id, payload.sender_user_id):
        if len(value) != 16:
            raise VaultError("vault payload identifier malformed")
    for value in (
        payload.publish_statement_sha256,
        payload.address_commitment,
        payload.key_commitment,
    ):
        if len(value) != 32:
            raise VaultError("vault payload digest malformed")
    if payload.claim_expires_at_epoch_seconds < 0:
        raise VaultError("vault payload expiry malformed")
    # Exact single enabled AES256_GCM/TINK serialized keyset. HPKE/Ed25519
    # keysets, ARK-shaped bytes, multi/disabled/malformed keys rejected.
    parse_exact_capsule_key(bytes(payload.capsule_aead_keyset))
    if len(payload.SerializeToString()) > MAX_ESCROW_PLAINTEXT_BYTES:
        raise VaultError("vault payload oversize")
    opening = payload.address_opening
    if opening.normalization_version != 1 or opening.channel not in (1, 2):
        raise VaultError("vault payload opening malformed")
    if not opening.normalized_destination or len(opening.salt) != 32:
        raise VaultError("vault payload opening malformed")


def verify_escrow_bindings(
    payload: dv2.PendingEscrowPlaintextV1, aad: dv2.PendingEscrowAadV1
) -> None:
    """Recompute D/KC and cross-check payload IDs/H/D/KC/expiry vs rebuilt AAD.

    Called before seal AND open. The AAD must have been rebuilt from the
    verified S; stored AAD is never trusted. Any mismatch fails closed.
    """
    validate_escrow_plaintext(payload)
    if (
        aad.aad_version != 1
        or aad.purpose != ESCROW_AAD_PURPOSE
        or aad.capsule_protocol_version != 2
        or aad.claim_policy_version != 1
    ):
        raise VaultError("vault AAD version unsupported")
    opening_bytes = payload.address_opening.SerializeToString(deterministic=True)
    recomputed_d = domains.domain_digest(domains.DELIVERY_ADDRESS_DOMAIN, opening_bytes)
    if recomputed_d != bytes(payload.address_commitment):
        raise VaultError("vault address commitment mismatch")
    kc_input = dv2.KeyCommitmentInput(
        version=1,
        capsule_id=bytes(payload.capsule_id),
        delivery_target_id=bytes(payload.delivery_target_id),
        capsule_aead_keyset=bytes(payload.capsule_aead_keyset),
    )
    recomputed_kc = domains.domain_digest(
        domains.DELIVERY_KEY_DOMAIN, kc_input.SerializeToString(deterministic=True)
    )
    if recomputed_kc != bytes(payload.key_commitment):
        raise VaultError("vault key commitment mismatch")
    if (
        bytes(payload.capsule_id) != bytes(aad.capsule_id)
        or bytes(payload.delivery_target_id) != bytes(aad.delivery_target_id)
        or bytes(payload.sender_user_id) != bytes(aad.sender_user_id)
        or bytes(payload.publish_statement_sha256) != bytes(aad.publish_statement_sha256)
        or bytes(payload.address_commitment) != bytes(aad.address_commitment)
        or bytes(payload.key_commitment) != bytes(aad.key_commitment)
        or payload.claim_expires_at_epoch_seconds != aad.claim_expires_at_epoch_seconds
    ):
        raise VaultError("vault payload/AAD binding mismatch")


@dataclass(frozen=True)
class SealedHandle:
    """Opaque stored escrow reference. No raw K, destination, or OTP."""

    vault: str
    key_id: str
    ciphertext: bytes
    binding_digest_hex: str


class EscrowVault:
    """Interface. Deposit role seals; claim role opens after authorization."""

    name = "escrow-vault"

    def seal(
        self, payload: dv2.PendingEscrowPlaintextV1, aad: dv2.PendingEscrowAadV1
    ) -> SealedHandle:
        raise NotImplementedError

    def open(
        self, handle: SealedHandle, aad: dv2.PendingEscrowAadV1
    ) -> dv2.PendingEscrowPlaintextV1:
        raise NotImplementedError


class FakeHandleVault(EscrowVault):
    """Opaque memory handles with NO encryption. Model-only unit tests.

    This is explicitly NOT encryption evidence and NEVER custody. Any use
    outside synthetic model tests is a misuse; prod/beta refused.
    """

    name = "fake-handle-v1"

    def __init__(self, *, mode: str) -> None:
        if mode in ("prod", "beta"):
            raise VaultError("fake vault forbidden outside dev/test models")
        self._mode = mode
        self._store: dict[int, bytes] = {}
        self._next = 1

    def seal(self, payload, aad) -> SealedHandle:
        verify_escrow_bindings(payload, aad)
        token = self._next
        self._next += 1
        self._store[token] = payload.SerializeToString(deterministic=True)
        return SealedHandle(
            vault=self.name,
            key_id="model-only",
            ciphertext=f"FAKE-HANDLE-{token}".encode("ascii"),
            binding_digest_hex=hashlib.sha256(escrow_aad_bytes(aad)).hexdigest(),
        )

    def open(self, handle, aad):
        if handle.vault != self.name:
            raise VaultError("vault handle mismatch")
        expected = hashlib.sha256(escrow_aad_bytes(aad)).hexdigest()
        if handle.binding_digest_hex != expected:
            raise VaultError("vault binding mismatch")
        try:
            token = int(handle.ciphertext.decode("ascii").rsplit("-", 1)[1])
            raw = self._store[token]
        except (ValueError, IndexError, KeyError) as exc:
            raise VaultError("vault handle unknown") from exc
        payload = dv2.PendingEscrowPlaintextV1.FromString(raw)
        verify_escrow_bindings(payload, aad)
        return payload


class DevSealedVault(EscrowVault):
    """Dev/test-only REAL AEAD sealing with a synthetic supplied config key.

    - Key: caller-supplied 32-byte synthetic value (test/CI config only).
    - Cipher: Tink AES256_GCM built from that explicit key material.
    - AAD: canonical PendingEscrowAadV1 bytes rebuilt from verified S.
    - Restart-safe: a new instance with the same config key opens handles
      sealed before restart (persistence/restart tests).
    - NOT production custody: same-host key, no HSM/KMS, no access-log
      boundary. Refuses prod/beta at construction. Never silently used
      where KMS custody is required.
    """

    name = "dev-aead-v1"

    def __init__(self, *, mode: str, key_id: str, key_32: bytes) -> None:
        if mode in ("prod", "beta"):
            raise VaultError("dev sealed vault forbidden in prod/beta")
        if not key_id or len(key_id) > 128:
            raise VaultError("dev vault key_id malformed")
        if len(key_32) != 32:
            raise VaultError("dev vault key must be 32 synthetic bytes")
        self._mode = mode
        self._key_id = key_id
        self._primitive = self._build_aead(key_32)

    @staticmethod
    def _build_aead(key_32: bytes):
        import tink
        from tink import aead
        from tink.proto import aes_gcm_pb2, tink_pb2

        key_proto = aes_gcm_pb2.AesGcmKey(version=0, key_value=key_32)
        keyset = tink_pb2.Keyset(primary_key_id=1)
        keyset.key.append(
            tink_pb2.Keyset.Key(
                key_data=tink_pb2.KeyData(
                    type_url="type.googleapis.com/google.crypto.tink.AesGcmKey",
                    value=key_proto.SerializeToString(),
                    key_material_type=tink_pb2.KeyData.SYMMETRIC,
                ),
                status=tink_pb2.ENABLED,
                key_id=1,
                output_prefix_type=tink_pb2.TINK,
            )
        )
        # Pinned tink==1.16.1 exposes no public cleartext-secret reader, so
        # the keyset is materialized via the version-pinned constructor and
        # verified by an immediate self-test probe. Any API drift fails
        # closed here instead of silently weakening the seal.
        try:
            handle = tink.KeysetHandle._create(keyset)  # noqa: SLF001 (pinned)
            primitive = handle.primitive(aead.Aead)
            probe = primitive.encrypt(b"dev-vault-selftest", b"dev-vault-selftest")
            assert primitive.decrypt(probe, b"dev-vault-selftest") == b"dev-vault-selftest"
        except Exception as exc:
            raise VaultError("dev vault cipher unavailable") from exc
        return primitive

    def seal(self, payload, aad) -> SealedHandle:
        verify_escrow_bindings(payload, aad)
        raw = payload.SerializeToString(deterministic=True)
        if len(raw) > MAX_ESCROW_PLAINTEXT_BYTES:
            raise VaultError("vault payload oversize")
        aad_bytes = escrow_aad_bytes(aad)
        ciphertext = self._primitive.encrypt(raw, aad_bytes)
        return SealedHandle(
            vault=self.name,
            key_id=self._key_id,
            ciphertext=ciphertext,
            binding_digest_hex=hashlib.sha256(aad_bytes).hexdigest(),
        )

    def open(self, handle, aad):
        if handle.vault != self.name or handle.key_id != self._key_id:
            raise VaultError("vault handle mismatch")
        aad_bytes = escrow_aad_bytes(aad)
        if handle.binding_digest_hex != hashlib.sha256(aad_bytes).hexdigest():
            raise VaultError("vault binding mismatch")
        import tink

        try:
            raw = self._primitive.decrypt(handle.ciphertext, aad_bytes)
        except tink.TinkError as exc:
            raise VaultError("vault open failed") from exc
        payload = dv2.PendingEscrowPlaintextV1.FromString(raw)
        verify_escrow_bindings(payload, aad)
        return payload


class ProductionKmsVault(EscrowVault):
    """Production escrow via independently configured KMS custody.

    Fail-closed: construction REQUIRES an explicitly injected kms_client
    and an allowlisted kms_key_id. There is no default, no dev fallback,
    no local-file key. Unconfigured use raises instead of sealing.
    KMS provisioning (concrete vendor/key/region/IAM) is an owner
    deployment choice, not part of this task.
    """

    name = "kms-v1"

    def __init__(self, *, kms_client, kms_key_id: str, allowed_key_ids: tuple[str, ...]) -> None:
        if kms_client is None:
            raise VaultError("production KMS custody not configured")
        if not kms_key_id or kms_key_id not in tuple(allowed_key_ids):
            raise VaultError("production KMS key not allowlisted")
        self._client = kms_client
        self._key_id = kms_key_id

    def seal(self, payload, aad) -> SealedHandle:
        verify_escrow_bindings(payload, aad)
        raw = payload.SerializeToString(deterministic=True)
        context = kms_context_for(aad)
        ciphertext = self._client.encrypt(
            key_id=self._key_id, plaintext=raw, encryption_context=context
        )
        return SealedHandle(
            vault=self.name,
            key_id=self._key_id,
            ciphertext=ciphertext,
            binding_digest_hex=context["binding"],
        )

    def open(self, handle, aad):
        if handle.vault != self.name or handle.key_id != self._key_id:
            raise VaultError("vault handle mismatch")
        context = kms_context_for(aad)
        if handle.binding_digest_hex != context["binding"]:
            raise VaultError("vault binding mismatch")
        raw = self._client.decrypt(
            key_id=self._key_id, ciphertext=handle.ciphertext, encryption_context=context
        )
        payload = dv2.PendingEscrowPlaintextV1.FromString(raw)
        verify_escrow_bindings(payload, aad)
        return payload


__all__ = [
    "DevSealedVault",
    "EscrowVault",
    "FakeHandleVault",
    "ProductionKmsVault",
    "SealedHandle",
    "VaultError",
    "build_escrow_aad",
    "escrow_aad_bytes",
    "kms_context_for",
    "parse_exact_capsule_key",
    "validate_escrow_plaintext",
    "verify_escrow_bindings",
    "domains",
]
