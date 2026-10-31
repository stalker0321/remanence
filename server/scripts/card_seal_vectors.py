"""Writes protocol/fixtures/card-seal-v1.json: fixed inputs and every derived
value, so Android and web prove byte-for-byte agreement with the reference.
Run from server/: .venv/bin/python scripts/card_seal_vectors.py"""

import base64
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))

import card_seal_client as device  # noqa: E402
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2  # noqa: E402

b64 = lambda raw: base64.b64encode(bytes(raw)).decode()  # noqa: E731

SECRET = "X4TP9RK"
PHONE = "+31612345612"
binding = dv2.CardSealAadV1(
    version=1,
    capsule_id=bytes(range(16)),
    delivery_target_id=bytes(range(16, 32)),
    sender_user_id=bytes(range(32, 48)),
    publish_statement_sha256=hashlib.sha256(b"statement").digest(),
    address_commitment=hashlib.sha256(b"D").digest(),
    key_commitment=hashlib.sha256(b"KC").digest(),
    claim_expires_at_epoch_seconds=1893456000,
)
opening = dv2.AddressOpening(normalization_version=1, channel=2, normalized_destination=PHONE, salt=bytes(range(32)))
keyset = bytes([0x20] * 90)
salt = hashlib.sha256(b"kdf salt").digest()
half = hashlib.sha256(b"server half").digest()
nonce = bytes(range(12))
seal = device.build_seal(
    secret=SECRET, keyset=keyset, opening=opening, binding=binding, salt=salt, server_half=half, nonce=nonce
)
kek, proof = device.derive(SECRET, seal.kdf, half, bytes(binding.publish_statement_sha256))
out = {
    "description": "ADR-020 card seal vector. Generated; do not edit.",
    "alphabet": device.ALPHABET,
    "secret": SECRET,
    "phone": PHONE,
    "code_display": device.format_code(SECRET, PHONE),
    "handwriting_inputs": {"x4t p9r k12": ["X4TP9RK", "12"], "0IL-BGS-Z12": ["0118652", "12"], "OIL-UGS-Z07": ["011V652", "07"]},
    "invalid_inputs": ["", "X4T", "X4T-P9R-K1X", "X4T-P9R-K123", "!!!-!!!-!12"],
    "kdf_salt_b64": b64(salt),
    "server_half_b64": b64(half),
    "nonce_b64": b64(nonce),
    "keyset_b64": b64(keyset),
    "opening_b64": b64(opening.SerializeToString(deterministic=True)),
    "binding_b64": b64(binding.SerializeToString(deterministic=True)),
    "statement_sha256_b64": b64(binding.publish_statement_sha256),
    "plaintext_b64": b64(dv2.CardSealPlaintextV1(version=1, capsule_aead_keyset=keyset, address_opening=opening).SerializeToString(deterministic=True)),
    "aad_b64": b64(device.aad_bytes(binding)),
    "kek_b64": b64(kek),
    "proof_b64": b64(proof),
    "verifier_b64": b64(seal.claim_proof_verifier),
    "sealed_key_b64": b64(seal.sealed_key),
    "seal_b64": b64(seal.SerializeToString(deterministic=True)),
}
target = Path(__file__).resolve().parents[2] / "protocol" / "fixtures" / "card-seal-v1.json"
target.write_text(json.dumps(out, indent=2) + "\n")
print(target)
