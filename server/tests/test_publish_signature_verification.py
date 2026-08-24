"""Cross-platform golden proof for the protocol-v1 publish signature (ADR-007).

The REST signature is exactly 69 bytes of raw Tink output with the TINK
output prefix: ``0x01 || key_id(4B big-endian) || r||s(64B)``. The backend
parses/verifies only; it never receives private material in production. The
checked-in fixture keysets are non-secret test material shared verbatim with
the Android golden test.
"""

import json
import pathlib

import pytest
import tink
from tink import signature, tink_config
from tink.proto import tink_pb2

FIXTURE = (
    pathlib.Path(__file__)
    .resolve()
    .parents[2]
    / "protocol"
    / "fixtures"
    / "publish-signature-v1.json"
)
DOMAIN_PREFIX = b"postmark/publish/v1"


@pytest.fixture(scope="module", autouse=True)
def _register_tink():
    tink_config.register()


@pytest.fixture(scope="module")
def golden() -> dict:
    return json.loads(FIXTURE.read_text())


@pytest.fixture(scope="module")
def verifying_handle(golden):
    return tink.KeysetHandle.read_no_secret(
        tink.JsonKeysetReader(json.dumps(golden["fixed_public_keyset_json"]))
    )


def _signature_input(golden: dict) -> bytes:
    return DOMAIN_PREFIX + bytes.fromhex(golden["expected_deterministic_hex"])


def _embedded_key_id(sig: bytes) -> int:
    return int.from_bytes(sig[1:5], "big")


def test_golden_signature_has_exact_protocol_v1_wire_format(golden):
    sig = bytes.fromhex(golden["expected_signature_hex"])

    assert len(sig) == 69
    assert sig[0] == 0x01  # TINK output prefix type
    assert _embedded_key_id(sig) == int(golden["signing_key_id"])


def test_backend_verifies_the_committed_android_golden_vector(golden, verifying_handle):
    verifier = verifying_handle.primitive(signature.PublicKeyVerify)

    # Must not raise; proves Android and backend agree byte-for-byte.
    verifier.verify(bytes.fromhex(golden["expected_signature_hex"]), _signature_input(golden))


def test_tampered_statement_fails_verification(golden, verifying_handle):
    statement = bytearray(bytes.fromhex(golden["expected_deterministic_hex"]))
    statement[len(statement) // 2] ^= 0x01
    verifier = verifying_handle.primitive(signature.PublicKeyVerify)

    with pytest.raises(tink.TinkError):
        verifier.verify(
            bytes.fromhex(golden["expected_signature_hex"]),
            DOMAIN_PREFIX + bytes(statement),
        )


def test_stripped_prefix_and_truncated_signature_fail_closed(golden, verifying_handle):
    sig = bytes.fromhex(golden["expected_signature_hex"])
    verifier = verifying_handle.primitive(signature.PublicKeyVerify)
    message = _signature_input(golden)

    with pytest.raises(tink.TinkError):
        verifier.verify(sig[5:], message)  # RAW-style stripped prefix
    with pytest.raises(tink.TinkError):
        verifier.verify(sig[:-1], message)
    with pytest.raises(tink.TinkError):
        verifier.verify(sig + b"\x00", message)


def test_wrong_embedded_key_id_is_rejected_before_verification(golden):
    sig = bytearray(bytes.fromhex(golden["expected_signature_hex"]))
    sig[4] ^= 0x01  # flip a key-id bit

    assert _embedded_key_id(bytes(sig)) != int(golden["signing_key_id"])
    keyset = golden["fixed_public_keyset_json"]
    assert all(
        entry["keyId"] != _embedded_key_id(bytes(sig)) for entry in keyset["key"]
    )
    handle = tink.KeysetHandle.read_no_secret(tink.JsonKeysetReader(json.dumps(keyset)))
    verifier = handle.primitive(signature.PublicKeyVerify)

    with pytest.raises(tink.TinkError):
        verifier.verify(bytes(sig), _signature_input(golden))


def test_fixture_public_half_matches_the_private_keyset_id(golden):
    public_keyset = golden["fixed_public_keyset_json"]
    private_keyset = golden["fixed_private_keyset_json"]

    assert public_keyset["primaryKeyId"] == private_keyset["primaryKeyId"]
    public_entry = public_keyset["key"][0]
    assert public_entry["keyData"]["typeUrl"] == (
        "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"
    )
    assert public_entry["keyData"]["keyMaterialType"] == "ASYMMETRIC_PUBLIC"
    assert public_entry["outputPrefixType"] == tink_pb2.OutputPrefixType.Name(
        tink_pb2.TINK
    )
