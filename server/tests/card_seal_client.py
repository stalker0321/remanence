"""Reference copy of the device side of ADR-020, for tests and the shared
vectors in protocol/fixtures. The server package deliberately has no such code."""

from __future__ import annotations

import hashlib
import hmac
import secrets

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from remanence.pending import domains
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

ALPHABET = "0123456789ACDEFHJKMNPQRTVWXY"
SECRET_LENGTH = 7
INPUT_MAP = {"O": "0", "I": "1", "L": "1", "B": "8", "G": "6", "S": "5", "Z": "2", "U": "V"}
KEK_INFO = b"remanence/card-seal/kek/v1\x00"
PROOF_INFO = b"remanence/card-seal/claim-proof/v1\x00"
AAD_DOMAIN = b"remanence/card-seal/aad/v1\x00"


def generate_secret() -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(SECRET_LENGTH))


def format_code(secret: str, phone_e164: str) -> str:
    whole = secret + phone_e164[-2:]
    return f"{whole[:3]}-{whole[3:6]}-{whole[6:]}"


def parse_code(typed: str) -> tuple[str, str]:
    """Returns (secret7, hint digits). Raises ValueError for anything not a code."""
    cleaned = "".join(ch for ch in typed.upper() if ch not in " -")
    mapped = "".join(INPUT_MAP.get(ch, ch) for ch in cleaned)
    if len(mapped) != SECRET_LENGTH + 2 or not mapped[-2:].isdigit():
        raise ValueError("not a code")
    if any(ch not in ALPHABET for ch in mapped):
        raise ValueError("not a code")
    return mapped[:SECRET_LENGTH], mapped[SECRET_LENGTH:]


def _hkdf_sha256(ikm: bytes, info: bytes) -> bytes:
    prk = hmac.new(bytes(32), ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()


def derive(secret: str, kdf: dv2.CardKdfV1, server_half: bytes, statement_sha256: bytes) -> tuple[bytes, bytes]:
    card_key = hash_secret_raw(
        secret=secret.encode("ascii"),
        salt=bytes(kdf.salt),
        time_cost=kdf.time_cost,
        memory_cost=kdf.memory_kib,
        parallelism=kdf.parallelism,
        hash_len=32,
        type=Type.ID,
        version=19,
    )
    ikm = card_key + server_half
    return _hkdf_sha256(ikm, KEK_INFO + statement_sha256), _hkdf_sha256(ikm, PROOF_INFO + statement_sha256)


def aad_bytes(binding: dv2.CardSealAadV1) -> bytes:
    return AAD_DOMAIN + binding.SerializeToString(deterministic=True)


def build_seal(
    *,
    secret: str,
    keyset: bytes,
    opening: dv2.AddressOpening,
    binding: dv2.CardSealAadV1,
    salt: bytes | None = None,
    server_half: bytes | None = None,
    nonce: bytes | None = None,
) -> dv2.PendingCardSealV1:
    kdf = dv2.CardKdfV1(
        version=1, time_cost=3, memory_kib=65536, parallelism=1, salt=salt or secrets.token_bytes(32)
    )
    half = server_half or secrets.token_bytes(32)
    kek, proof = derive(secret, kdf, half, bytes(binding.publish_statement_sha256))
    plaintext = dv2.CardSealPlaintextV1(version=1, capsule_aead_keyset=keyset, address_opening=opening)
    iv = nonce or secrets.token_bytes(12)
    sealed = iv + AESGCM(kek).encrypt(iv, plaintext.SerializeToString(deterministic=True), aad_bytes(binding))
    return dv2.PendingCardSealV1(
        version=1,
        binding=binding,
        kdf=kdf,
        server_half=half,
        sealed_key=sealed,
        claim_proof_verifier=hashlib.sha256(proof).digest(),
    )


def open_seal(secret: str, seal: dv2.PendingCardSealV1) -> tuple[dv2.CardSealPlaintextV1, bytes]:
    """Raises cryptography's InvalidTag for a wrong code."""
    kek, proof = derive(secret, seal.kdf, bytes(seal.server_half), bytes(seal.binding.publish_statement_sha256))
    raw = AESGCM(kek).decrypt(bytes(seal.sealed_key[:12]), bytes(seal.sealed_key[12:]), aad_bytes(seal.binding))
    return dv2.CardSealPlaintextV1.FromString(raw), proof


__all__ = ["domains"]
