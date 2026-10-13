"""T185 dev service-claim pin: fixture consistency + cross-key rejection.

`protocol/fixtures/delivery-v2-dev-service-pin.json` carries the
authorized PUBLIC synthetic dev signer pin (Tink Ed25519 public
keyset) + deployment UUID for T186. This test proves the stored pin
parses as a secretless Ed25519 public keyset, the key id is a UUID
(not a numeric Tink key id), and a signature minted under a DIFFERENT
key does NOT verify under the pin (R-carried keys are never trust
roots). Synthetic data only; no database, no network.
"""

from __future__ import annotations

import base64
import io
import json
import uuid
from pathlib import Path

import tink
from tink import signature, tink_config

from remanence.pending import domains

FIXDIR = Path(__file__).resolve().parents[2] / "protocol" / "fixtures"


def _load() -> dict:
    return json.loads((FIXDIR / "delivery-v2-dev-service-pin.json").read_text())


def test_dev_service_pin_parses_and_rejects_foreign_key() -> None:
    tink_config.register()
    pin = _load()
    assert pin["note"].startswith("TEST-ONLY")
    # Deployment UUID form, never a numeric Tink key id.
    key_id = uuid.UUID(pin["service_key_id"])
    assert str(key_id) == pin["service_key_id"]
    assert pin["claim_binding_domain"] == domains.CLAIM_BINDING_DOMAIN
    # Stored pin parses as a secretless public keyset (same reader the
    # server verifier uses; unknown fields would raise here).
    public_handle = tink.KeysetHandle.read_no_secret(
        tink.BinaryKeysetReader(base64.b64decode(pin["service_ed25519_public_keyset_b64"]))
    )
    buf = io.BytesIO()
    public_handle.write_no_secret(tink.BinaryKeysetWriter(buf))
    assert buf.getvalue() == base64.b64decode(pin["service_ed25519_public_keyset_b64"])
    verifier = public_handle.primitive(signature.PublicKeyVerify)
    # Foreign key mint: must NOT verify under the pinned dev verifier.
    foreign = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    forged = foreign.primitive(signature.PublicKeySign).sign(b"not-the-claim-binding")
    import tink as _tink

    try:
        verifier.verify(forged, b"not-the-claim-binding")
    except _tink.TinkError:
        return
    raise AssertionError("foreign-key signature verified under the dev pin")
