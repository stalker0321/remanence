"""First-gift card seal (ADR-020). Fail-closed, no plaintext logging.

The sender's device seals the capsule key under the code on the card plus a
random server half. The server stores the seal encrypted under an application
key, hands it out only after phone proof, and deletes it at claim, cancel or
expiry. It never receives the code, so it cannot open the seal without
brute-forcing it. Derivation lives on the clients; tests hold a reference copy.
"""

from __future__ import annotations

import hashlib
import hmac
from dataclasses import dataclass

from remanence.pending.canonical import CanonicalError, parse_strict
from remanence.pending.vault import SealedHandle, VaultError, aead_from_raw_key, escrow_aad_bytes
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

VAULT_NAME = "card-seal-v1"
KDF_TIME_COST = 3
KDF_MEMORY_KIB = 65536
KDF_PARALLELISM = 1
MAX_SEAL_BYTES = 2048
MAX_SEALED_CODE_BYTES = 512
# nonce 12 + tag 16 + at least a little plaintext; K plus opening stays far below.
_MIN_SEALED_KEY = 12 + 16 + 1
_MAX_SEALED_KEY = 1024
_KEY_ID = "app-key-v1"
_AT_REST_CONTEXT = b"remanence/card-seal/at-rest/v1\x00"


class CardSealError(RuntimeError):
    def __init__(self, code: str = "PROTOCOL_UNSUPPORTED") -> None:
        super().__init__("card seal refused")
        self.code = code


def parse_seal(raw: bytes) -> dv2.PendingCardSealV1:
    """Strict canonical parse plus exact parameters. Weaker KDF settings are
    refused here and again on the recipient device."""
    try:
        seal = parse_strict(dv2.PendingCardSealV1, raw, max_bytes=MAX_SEAL_BYTES)
    except CanonicalError as exc:
        raise CardSealError() from exc
    kdf = seal.kdf
    if (
        seal.version != 1
        or kdf.version != 1
        or kdf.time_cost != KDF_TIME_COST
        or kdf.memory_kib != KDF_MEMORY_KIB
        or kdf.parallelism != KDF_PARALLELISM
        or len(kdf.salt) != 32
        or len(seal.server_half) != 32
        or len(seal.claim_proof_verifier) != 32
        or not _MIN_SEALED_KEY <= len(seal.sealed_key) <= _MAX_SEALED_KEY
    ):
        raise CardSealError()
    return seal


def verify_binding(seal: dv2.PendingCardSealV1, statement, statement_sha256: bytes) -> None:
    """The seal must name exactly this signed publication."""
    binding = seal.binding
    if (
        binding.version != 1
        or bytes(binding.capsule_id) != bytes(statement.capsule_id)
        or bytes(binding.delivery_target_id) != bytes(statement.delivery_target_id)
        or bytes(binding.sender_user_id) != bytes(statement.sender_user_id)
        or bytes(binding.publish_statement_sha256) != statement_sha256
        or bytes(binding.address_commitment) != bytes(statement.address_commitment)
        or bytes(binding.key_commitment) != bytes(statement.key_commitment)
        or binding.claim_expires_at_epoch_seconds != statement.claim_expires_at_epoch_seconds
    ):
        raise CardSealError()


def proof_matches(seal: dv2.PendingCardSealV1, proof: bytes) -> bool:
    if len(proof) != 32:
        return False
    return hmac.compare_digest(hashlib.sha256(proof).digest(), bytes(seal.claim_proof_verifier))


@dataclass(frozen=True)
class CardSealVault:
    """Encrypts the seal at rest under an application key, so a database leak
    alone yields neither the server half nor the sealed key."""

    key_32: bytes
    name: str = VAULT_NAME

    def __post_init__(self) -> None:
        if len(self.key_32) != 32:
            raise VaultError("card seal key must be 32 bytes")

    def __repr__(self) -> str:
        return "CardSealVault(<redacted>)"

    def seal(self, seal_raw: bytes, aad: dv2.PendingEscrowAadV1) -> SealedHandle:
        aad_bytes = _AT_REST_CONTEXT + escrow_aad_bytes(aad)
        return SealedHandle(
            vault=self.name,
            key_id=_KEY_ID,
            ciphertext=aead_from_raw_key(self.key_32).encrypt(seal_raw, aad_bytes),
            binding_digest_hex=hashlib.sha256(aad_bytes).hexdigest(),
        )

    def open_seal(self, handle: SealedHandle, aad: dv2.PendingEscrowAadV1) -> dv2.PendingCardSealV1:
        import tink

        if handle.vault != self.name or handle.key_id != _KEY_ID:
            raise VaultError("vault handle mismatch")
        aad_bytes = _AT_REST_CONTEXT + escrow_aad_bytes(aad)
        if handle.binding_digest_hex != hashlib.sha256(aad_bytes).hexdigest():
            raise VaultError("vault binding mismatch")
        try:
            raw = aead_from_raw_key(self.key_32).decrypt(handle.ciphertext, aad_bytes)
        except tink.TinkError as exc:
            raise VaultError("vault open failed") from exc
        return parse_seal(raw)
