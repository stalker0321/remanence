"""T185 cross-language golden vector: stored dev-vault ciphertext decrypts.

The vector in protocol/fixtures/delivery-v2-dev-vault-vector.json was
sealed in Python with fixed synthetic TEST-ONLY key material. This test
proves the stored bytes (not a fresh round trip) open to the stored
plaintext under a rebuilt instance — the same bytes Kotlin/Tink must
accept. Synthetic data only; no database, no network.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

import tink
from tink import tink_config

from remanence.pending.vault import DevSealedVault, SealedHandle, escrow_aad_bytes
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2

FIXDIR = Path(__file__).resolve().parents[2] / "protocol" / "fixtures"


def _load() -> dict:
    return json.loads((FIXDIR / "delivery-v2-dev-vault-vector.json").read_text())


def test_stored_ciphertext_opens_to_stored_plaintext() -> None:
    tink_config.register()
    vector = _load()
    assert vector["vault"] == "dev-aead-v1"
    assert vector["note"].startswith("TEST-ONLY")
    aad = dv2.PendingEscrowAadV1.FromString(bytes.fromhex(vector["aad_canonical_hex"]))
    handle = SealedHandle(
        vault=vector["vault"],
        key_id=vector["key_id"],
        ciphertext=base64.b64decode(vector["ciphertext_b64"]),
        binding_digest_hex=vector["binding_hex"],
    )
    # Fresh rebuilt instance (restart semantics) with the stored TEST-ONLY key.
    vault = DevSealedVault(
        mode="test",
        key_id=vector["key_id"],
        key_32=bytes.fromhex(vector["dev_key_32_hex_TEST_ONLY"]),
    )
    opened = vault.open(handle, aad)
    assert opened.SerializeToString(deterministic=True).hex() == vector["payload_plaintext_hex"]
    import hashlib

    assert handle.binding_digest_hex == hashlib.sha256(escrow_aad_bytes(aad)).hexdigest()
