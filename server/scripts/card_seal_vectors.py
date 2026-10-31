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
# Wire shapes the clients hand-build or hand-parse.
ids = {name: bytes([index]) * 16 for index, name in enumerate(
    ["capsule", "target", "sender", "sender_bundle", "recipient", "recipient_bundle", "blob", "photo", "operation", "proof", "challenge", "session", "claim", "service"], 1)}
statement = dv2.PendingPublishStatementV2(
    protocol_version=2, capsule_id=ids["capsule"], sender_user_id=ids["sender"], sender_key_bundle_id=ids["sender_bundle"],
    delivery_target_id=ids["target"], address_commitment=hashlib.sha256(b"D").digest(), key_commitment=hashlib.sha256(b"KC").digest(),
    created_at_epoch_seconds=1893000000, claim_expires_at_epoch_seconds=1893456000, claim_policy_version=1, claim_signing_key_id=ids["service"],
    artifacts=[
        dv2.ArtifactBindingV2(blob_id=ids["blob"], kind=1, ordinal=-1, ciphertext_size=1000, ciphertext_sha256=bytes([1]) * 32),
        dv2.ArtifactBindingV2(blob_id=ids["photo"], kind=3, ordinal=4, ciphertext_size=3000000, ciphertext_sha256=bytes([2]) * 32),
    ],
)
nonce32 = bytes([9]) * 32
intent = dv2.ClaimIntentV2(
    intent_version=1, operation_id=ids["operation"], capsule_id=ids["capsule"], delivery_target_id=ids["target"],
    publish_statement_sha256=bytes([3]) * 32, proof_id=ids["proof"], challenge_id=ids["challenge"], recipient_user_id=ids["recipient"],
    recipient_key_bundle_id=ids["recipient_bundle"], session_id=ids["session"], nonce=nonce32, nonce_sha256=hashlib.sha256(nonce32).digest(), bind_alias=True,
)
context = dv2.ClaimedEnvelopeContextV2(
    protocol_version=2, capsule_id=ids["capsule"], delivery_target_id=ids["target"], sender_user_id=ids["sender"],
    recipient_user_id=ids["recipient"], recipient_key_bundle_id=ids["recipient_bundle"], publish_statement_sha256=bytes([3]) * 32, claim_binding_sha256=bytes([4]) * 32,
)
binding_v1 = dv2.ClaimBindingV1(
    binding_version=1, capsule_protocol_version=2, capsule_id=ids["capsule"], delivery_target_id=ids["target"], publish_statement_sha256=bytes([3]) * 32,
    address_commitment=hashlib.sha256(b"D").digest(), recipient_user_id=ids["recipient"], recipient_key_bundle_id=ids["recipient_bundle"],
    recipient_encryption_public_keyset_sha256=bytes([5]) * 32, claim_id=ids["claim"], claimed_at_epoch_seconds=1893000500, claim_policy_version=1, claim_signing_key_id=ids["service"],
)
challenge = dv2.ClaimChallengeV1(
    challenge_id=ids["challenge"], capsule_id=ids["capsule"], delivery_target_id=ids["target"], recipient_user_id=ids["recipient"],
    recipient_key_bundle_id=ids["recipient_bundle"], session_id=ids["session"], nonce_sha256=hashlib.sha256(nonce32).digest(), expires_at_epoch_seconds=1893000800,
)
artifact_aad = dv2.ArtifactAadV2(protocol_version=2, capsule_id=ids["capsule"], blob_id=ids["blob"], artifact_kind=1, ordinal=-1, sender_user_id=ids["sender"], delivery_target_id=ids["target"])
key_commitment_input = dv2.KeyCommitmentInput(version=1, capsule_id=ids["capsule"], delivery_target_id=ids["target"], capsule_aead_keyset=keyset)
out["wire"] = {
    "ids_b64": {name: b64(value) for name, value in ids.items()},
    "statement_b64": b64(statement.SerializeToString(deterministic=True)),
    "intent_b64": b64(intent.SerializeToString(deterministic=True)),
    "context_b64": b64(context.SerializeToString(deterministic=True)),
    "binding_b64": b64(binding_v1.SerializeToString(deterministic=True)),
    "challenge_b64": b64(challenge.SerializeToString(deterministic=True)),
    "nonce_b64": b64(nonce32),
    "artifact_aad_b64": b64(b"postmark/artifact/v2\x00" + artifact_aad.SerializeToString(deterministic=True)),
    "key_commitment_b64": b64(hashlib.sha256(b"postmark/delivery-key/v1\x00" + key_commitment_input.SerializeToString(deterministic=True)).digest()),
    "address_commitment_b64": b64(hashlib.sha256(b"postmark/delivery-address/v1\x00" + opening.SerializeToString(deterministic=True)).digest()),
}
target = Path(__file__).resolve().parents[2] / "protocol" / "fixtures" / "card-seal-v1.json"
target.write_text(json.dumps(out, indent=2) + "\n")
print(target)
