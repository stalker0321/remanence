"""Restartable test-custody state bundle (T205, writer-built, root-run).

Owner explicitly approved ONE isolated test backend with persisted
server keys (synthetic test custody ONLY — never production-grade,
never KMS backup). This module owns the versioned on-disk bundle;
the launcher (`durable_bootstrap.py`) owns process composition.
Root provisions the DB, installs the OS service, and runs everything.

Design (all fail-closed, exit-2 class refusals, no secrets in
messages):
- `init_bundle()`: exclusive creation into an ABSENT state dir
  (atomic `mkdir`; a racing second init fails) + exclusive atomic
  file write (`O_EXCL` temp, 0600 enforced, fsync, atomic replace,
  dir fsync). Existing state is never overwritten.
- `load_bundle()`: strict validation (dir/file ownership and modes,
  no symlinks, bounded size, exact schema version and key set,
  key-length and UUID checks, Tink private-keyset strict parse) and
  BINDING comparison against the caller-supplied expected deployment
  (DB identity, blob root, mode, Google audience/presenters,
  recovery RP/origins, HMAC equality). Anything missing, corrupt,
  permissive, symlinked, or mismatched fails closed — keys are NEVER
  regenerated, rotated, recovered, or partially overwritten here.
- Service identity stays 100% Tink Ed25519 with identical framing:
  init generates via Tink, exports through the public
  `tink.cleartext_keyset_handle` serialization into the explicit
  private 0600 file (permitted by owner for this synthetic test
  custody only — never production), and resume re-imports through
  the same public module with strict validation. No raw-Ed25519
  variant, no new protocol signer: the unchanged server verifier
  accepts both sides' artifacts. (tink is pinned `==1.16.1` in
  `server/uv.lock`; the round-trip test fails loudly on drift.)

Material inventory (from real create_app + session/recovery/pending
source — see tasks/T205/result.md): persisted here are ONLY the
server-side continuity secrets (pending HMAC/destination/OTP/proof/
evidence/locator/vault keys, service private keyset + UUID). DB-backed
rows (opaque session tokens, recovery/Google/claim challenges, proof
attempts, idempotency, locators, bindings) and per-request nonces/OTP
codes need no persistence; in-memory reservations/rate limits are
documented ephemeral (client retries).
"""

from __future__ import annotations

import base64
import hmac
import io
import json
import os
import secrets
import stat
import tink
import uuid
from datetime import datetime, timezone

BUNDLE_FILENAME = "bundle.json"
BUNDLE_SCHEMA_VERSION = 1
MAX_BUNDLE_BYTES = 16 * 1024

# Exact bundle keys: metadata + binding + key material. No extras
# are ever accepted on load (strict, fail closed).
BUNDLE_KEYS = frozenset(
    {
        "schema_version",
        "created_at",
        "service_key_id",
        "vault_key_id",
        "db_name",
        "db_host",
        "db_port",
        "blob_root",
        "mode",
        "google_audience",
        "google_presenters",
        "recovery_rp_id",
        "recovery_origins",
        "hmac_key_b64",
        "destination_key_b64",
        "otp_key_b64",
        "proof_mac_key_b64",
        "evidence_key_b64",
        "locator_code_key_b64",
        "vault_key_b64",
        "service_private_keyset_b64",
        "service_public_keyset_b64",
    }
)


class DurableRefusal(Exception):
    """Fail-closed durable-state refusal (fixed messages only — never
    file content, secrets, or exception strings)."""


def _b64decode_exact(value: object, *, length: int | None, name: str) -> bytes:
    if not isinstance(value, str):
        raise DurableRefusal(f"refusing bundle: bad {name}")
    try:
        raw = base64.b64decode(value.encode("ascii"), validate=True)
    except Exception:
        raise DurableRefusal(f"refusing bundle: bad {name}") from None
    if length is not None and len(raw) != length:
        raise DurableRefusal(f"refusing bundle: bad {name}")
    return raw


def _require_uuid(value: object, name: str) -> uuid.UUID:
    try:
        return uuid.UUID(str(value))
    except (ValueError, AttributeError, TypeError):
        raise DurableRefusal(f"refusing bundle: bad {name}") from None


def export_service_keyset(private_handle) -> tuple[bytes, bytes]:
    """Export (private keyset bytes, public keyset bytes) from a Tink
    Ed25519 private handle, both strictly validated. The private bytes
    go ONLY to the explicit 0600 bundle file; the public bytes feed
    the allowlisted public pin. Raises DurableRefusal on any shape
    deviation (single ENABLED TINK Ed25519 key only). Uses the public
    `tink.cleartext_keyset_handle` serialization (permitted by owner
    for this synthetic test custody only — never production)."""
    import tink
    from tink import cleartext_keyset_handle

    buf = io.BytesIO()
    cleartext_keyset_handle.write(tink.BinaryKeysetWriter(buf), private_handle)
    private_raw = buf.getvalue()
    _validate_private_keyset_bytes(private_raw)
    public_handle = private_handle.public_keyset_handle()
    buf = io.BytesIO()
    public_handle.write_no_secret(tink.BinaryKeysetWriter(buf))
    public_raw = buf.getvalue()
    _validate_public_keyset(public_raw)
    return private_raw, public_raw


def _validate_private_keyset_bytes(private_raw: bytes) -> None:
    """Strict profile for persisted service private keyset bytes:
    canonical single ENABLED TINK Ed25519 private key, 32-byte seed
    plus 32-byte public part, no unknown fields."""
    from tink.proto import ed25519_pb2, tink_pb2

    try:
        keyset = tink_pb2.Keyset.FromString(bytes(private_raw))
    except Exception:
        raise DurableRefusal("refusing signer: keyset parse") from None
    probe = tink_pb2.Keyset()
    probe.CopyFrom(keyset)
    probe.DiscardUnknownFields()
    if keyset.SerializeToString(deterministic=True) != bytes(private_raw):
        raise DurableRefusal("refusing signer: non-canonical bytes")
    if probe.SerializeToString(deterministic=True) != bytes(private_raw):
        raise DurableRefusal("refusing signer: unknown fields")
    if len(keyset.key) != 1:
        raise DurableRefusal("refusing signer: key count")
    key = keyset.key[0]
    if key.key_id == 0 or keyset.primary_key_id != key.key_id:
        raise DurableRefusal("refusing signer: key id")
    if key.output_prefix_type != tink_pb2.TINK:
        raise DurableRefusal("refusing signer: prefix type")
    if (
        key.status != tink_pb2.ENABLED
        or key.key_data.key_material_type != tink_pb2.KeyData.ASYMMETRIC_PRIVATE
        or key.key_data.type_url != "type.googleapis.com/google.crypto.tink.Ed25519PrivateKey"
    ):
        raise DurableRefusal("refusing signer: key profile")
    try:
        inner = ed25519_pb2.Ed25519PrivateKey.FromString(key.key_data.value)
    except Exception:
        raise DurableRefusal("refusing signer: key parse") from None
    if (
        inner.version != 0
        or len(inner.key_value) != 32
        or len(inner.public_key.key_value) != 32
    ):
        raise DurableRefusal("refusing signer: key material")


def _validate_public_keyset(public_raw: bytes) -> None:
    """Strict profile for the public pin bytes: canonical single
    ENABLED TINK Ed25519 key, 32-byte material, no unknown fields —
    the same bar as the server verifier."""
    from tink.proto import ed25519_pb2, tink_pb2

    try:
        keyset = tink_pb2.Keyset.FromString(public_raw)
    except Exception:
        raise DurableRefusal("refusing pin: keyset parse") from None
    probe = tink_pb2.Keyset()
    probe.CopyFrom(keyset)
    probe.DiscardUnknownFields()
    if keyset.SerializeToString(deterministic=True) != bytes(public_raw):
        raise DurableRefusal("refusing pin: non-canonical bytes")
    if probe.SerializeToString(deterministic=True) != bytes(public_raw):
        raise DurableRefusal("refusing pin: unknown fields")
    if len(keyset.key) != 1:
        raise DurableRefusal("refusing pin: key count")
    key = keyset.key[0]
    if key.key_id == 0 or keyset.primary_key_id != key.key_id:
        raise DurableRefusal("refusing pin: key id")
    if key.output_prefix_type != tink_pb2.TINK:
        raise DurableRefusal("refusing pin: prefix type")
    if (
        key.status != tink_pb2.ENABLED
        or key.key_data.key_material_type != tink_pb2.KeyData.ASYMMETRIC_PUBLIC
        or key.key_data.type_url != "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"
    ):
        raise DurableRefusal("refusing pin: key profile")
    try:
        inner = ed25519_pb2.Ed25519PublicKey.FromString(key.key_data.value)
    except Exception:
        raise DurableRefusal("refusing pin: key parse") from None
    if len(inner.key_value) != 32 or inner.version != 0:
        raise DurableRefusal("refusing pin: key material")


def import_service_signer(private_raw: bytes):
    """Rebuild the private signing handle from bundle bytes (strictly
    validated first) through the public `tink.cleartext_keyset_handle`
    module — no private API, no raw-crypto variant."""
    import tink
    from tink import cleartext_keyset_handle

    _validate_private_keyset_bytes(private_raw)
    try:
        return cleartext_keyset_handle.read(tink.BinaryKeysetReader(bytes(private_raw)))
    except Exception:
        raise DurableRefusal("refusing signer: keyset import") from None


def generate_epoch_material() -> dict:
    """Generate one fresh epoch's server-side continuity material (no
    I/O): CSPRNG symmetric keys + a real Tink Ed25519 service identity
    (private keyset bytes + public pin bytes + deployment UUID). The
    HMAC caller secret is NOT generated here — root supplies it per
    start (or per epoch) and resume binds it by equality."""
    from tink import signature, tink_config

    tink_config.register()
    private_handle = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    private_raw, public_raw = export_service_keyset(private_handle)
    # Prove the export path immediately: the stored bytes re-import
    # to a working signer (same tripwire resume relies on).
    signer = import_service_signer(private_raw).primitive(signature.PublicKeySign)
    return {
        "service_key_id": uuid.uuid4(),
        "vault_key_id": "durable-test-vault-1",
        "destination_key": secrets.token_bytes(32),
        "otp_key": secrets.token_bytes(32),
        "proof_mac_key": secrets.token_bytes(32),
        "evidence_key": secrets.token_bytes(32),
        "locator_code_key": secrets.token_bytes(32),
        "vault_key": secrets.token_bytes(32),
        "service_private_keyset": private_raw,
        "service_public_keyset": base64.b64encode(public_raw).decode("ascii"),
        "service_signer": signer,
    }


def build_bundle(*, material: dict, binding: dict, hmac_key: bytes) -> dict:
    """Assemble the strict versioned bundle dict (pure, no I/O).
    `binding` carries the exact deployment identifiers; `hmac_key`
    is the caller-supplied secret (bytes length >= 32, stored
    byte-identical)."""
    if not isinstance(hmac_key, bytes) or len(hmac_key) < 32:
        raise DurableRefusal("refusing bundle: bad hmac key")
    for name in (
        "destination_key", "otp_key", "proof_mac_key",
        "evidence_key", "locator_code_key", "vault_key",
    ):
        value = material.get(name)
        if not isinstance(value, bytes) or len(value) != 32:
            raise DurableRefusal(f"refusing bundle: bad {name}")
    for name in ("service_private_keyset",):
        if not isinstance(material.get(name), bytes) or not material[name]:
            raise DurableRefusal(f"refusing bundle: bad {name}")
    public_b64 = material.get("service_public_keyset")
    if not isinstance(public_b64, str) or not public_b64:
        raise DurableRefusal("refusing bundle: bad service_public_keyset")
    try:
        public_raw = base64.b64decode(public_b64.encode("ascii"), validate=True)
    except Exception:
        raise DurableRefusal("refusing bundle: bad service_public_keyset") from None
    _validate_public_keyset(public_raw)
    for name in (
        "db_name", "db_host", "db_port", "blob_root", "mode",
        "google_audience", "recovery_rp_id",
    ):
        if not isinstance(binding.get(name), str) or not binding[name]:
            raise DurableRefusal(f"refusing bundle: bad {name}")
    for name in ("google_presenters", "recovery_origins"):
        if (
            not isinstance(binding.get(name), list)
            or not binding[name]
            or any(not isinstance(item, str) or not item for item in binding[name])
        ):
            raise DurableRefusal(f"refusing bundle: bad {name}")
    bundle = {
        "schema_version": BUNDLE_SCHEMA_VERSION,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "service_key_id": str(material["service_key_id"]),
        "vault_key_id": material["vault_key_id"],
        "db_name": binding["db_name"],
        "db_host": binding["db_host"],
        "db_port": binding["db_port"],
        "blob_root": binding["blob_root"],
        "mode": binding["mode"],
        "google_audience": binding["google_audience"],
        "google_presenters": list(binding["google_presenters"]),
        "recovery_rp_id": binding["recovery_rp_id"],
        "recovery_origins": list(binding["recovery_origins"]),
        "hmac_key_b64": base64.b64encode(hmac_key).decode("ascii"),
        "destination_key_b64": base64.b64encode(material["destination_key"]).decode("ascii"),
        "otp_key_b64": base64.b64encode(material["otp_key"]).decode("ascii"),
        "proof_mac_key_b64": base64.b64encode(material["proof_mac_key"]).decode("ascii"),
        "evidence_key_b64": base64.b64encode(material["evidence_key"]).decode("ascii"),
        "locator_code_key_b64": base64.b64encode(material["locator_code_key"]).decode("ascii"),
        "vault_key_b64": base64.b64encode(material["vault_key"]).decode("ascii"),
        "service_private_keyset_b64": base64.b64encode(material["service_private_keyset"]).decode("ascii"),
        "service_public_keyset_b64": public_b64,
    }
    if set(bundle) != BUNDLE_KEYS:
        raise DurableRefusal("refusing bundle: key set")
    _require_uuid(bundle["service_key_id"], "service_key_id")
    if len(base64.b64decode(bundle["hmac_key_b64"])) < 32:
        raise DurableRefusal("refusing bundle: bad hmac key")
    return bundle


def init_bundle(state_dir: str, bundle: dict) -> str:
    """Exclusive atomic init into an ABSENT state dir. The dir is
    created atomically (a racing second init fails); the bundle file
    is written via O_EXCL temp + 0600 + fsync + atomic replace + dir
    fsync. Existing state is never overwritten. Returns the file path."""
    if not state_dir or not isinstance(state_dir, str):
        raise DurableRefusal("refusing init: no state dir")
    try:
        os.mkdir(state_dir, 0o700)
    except FileExistsError:
        raise DurableRefusal("refusing init: state dir exists") from None
    except OSError:
        raise DurableRefusal("refusing init: state dir unusable") from None
    try:
        os.chmod(state_dir, 0o700)
    except OSError:
        raise DurableRefusal("refusing init: state dir unusable") from None
    if set(bundle) != BUNDLE_KEYS:
        raise DurableRefusal("refusing init: bundle key set")
    target = os.path.join(state_dir, BUNDLE_FILENAME)
    tmp_path = os.path.join(state_dir, BUNDLE_FILENAME + ".tmp")
    payload = json.dumps(bundle, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    if len(payload) > MAX_BUNDLE_BYTES:
        raise DurableRefusal("refusing init: bundle oversize")
    try:
        fd = os.open(tmp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise DurableRefusal("refusing init: state collision") from None
    except OSError:
        raise DurableRefusal("refusing init: state unwritable") from None
    try:
        os.fchmod(fd, 0o600)
        os.write(fd, payload)
        os.fsync(fd)
    finally:
        try:
            os.close(fd)
        except OSError:
            pass
    try:
        os.replace(tmp_path, target)
        dir_fd = os.open(state_dir, os.O_RDONLY)
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
    except OSError:
        raise DurableRefusal("refusing init: state uncommittable") from None
    return target


def _check_state_file(path: str) -> os.stat_result:
    try:
        file_stat = os.lstat(path)
    except OSError:
        raise DurableRefusal("refusing state: unreadable") from None
    if (
        not stat.S_ISREG(file_stat.st_mode)
        or stat.S_ISLNK(file_stat.st_mode)
        or file_stat.st_uid not in (0, os.geteuid())
        or file_stat.st_mode & 0o077
        or file_stat.st_size > MAX_BUNDLE_BYTES
    ):
        raise DurableRefusal("refusing state: insecure bundle file") from None
    return file_stat


def load_bundle(state_dir: str, *, expected: dict) -> dict:
    """Strict resume: validate everything, compare the binding, return
    decoded material (never the raw file). Anything missing, corrupt,
    permissive, symlinked, or mismatched fails closed — keys are never
    regenerated here. `expected` carries db_name/db_host/db_port/
    blob_root/mode/google_audience/google_presenters/recovery_rp_id/
    recovery_origins/hmac_key."""
    if not state_dir or not isinstance(state_dir, str):
        raise DurableRefusal("refusing state: no state dir")
    resolved = os.path.abspath(state_dir)
    try:
        if os.path.realpath(resolved) != resolved:
            raise DurableRefusal("refusing state: symlink escape")
    except DurableRefusal:
        raise
    except OSError:
        raise DurableRefusal("refusing state: unreadable") from None
    try:
        dir_stat = os.lstat(resolved)
    except OSError:
        raise DurableRefusal("refusing state: unreadable") from None
    if (
        not stat.S_ISDIR(dir_stat.st_mode)
        or stat.S_ISLNK(dir_stat.st_mode)
        or dir_stat.st_uid not in (0, os.geteuid())
        or dir_stat.st_mode & 0o022
    ):
        raise DurableRefusal("refusing state: insecure state dir") from None
    target = os.path.join(resolved, BUNDLE_FILENAME)
    _check_state_file(target)
    try:
        with open(target, encoding="utf-8") as handle:
            bundle = json.load(handle)
    except (OSError, ValueError, UnicodeDecodeError):
        raise DurableRefusal("refusing state: bundle unreadable") from None
    if not isinstance(bundle, dict) or set(bundle) != BUNDLE_KEYS:
        raise DurableRefusal("refusing state: bundle shape") from None
    if bundle.get("schema_version") != BUNDLE_SCHEMA_VERSION:
        raise DurableRefusal("refusing state: bundle version") from None
    service_key_id = _require_uuid(bundle.get("service_key_id"), "service_key_id")
    hmac_key = _b64decode_exact(bundle.get("hmac_key_b64"), length=None, name="hmac key")
    if len(hmac_key) < 32:
        raise DurableRefusal("refusing state: bad hmac key")
    material = {
        "service_key_id": service_key_id,
        "vault_key_id": bundle.get("vault_key_id"),
        "destination_key": _b64decode_exact(bundle.get("destination_key_b64"), length=32, name="destination key"),
        "otp_key": _b64decode_exact(bundle.get("otp_key_b64"), length=32, name="otp key"),
        "proof_mac_key": _b64decode_exact(bundle.get("proof_mac_key_b64"), length=32, name="proof key"),
        "evidence_key": _b64decode_exact(bundle.get("evidence_key_b64"), length=32, name="evidence key"),
        "locator_code_key": _b64decode_exact(bundle.get("locator_code_key_b64"), length=32, name="locator key"),
        "vault_key": _b64decode_exact(bundle.get("vault_key_b64"), length=32, name="vault key"),
    }
    if not isinstance(material["vault_key_id"], str) or not material["vault_key_id"]:
        raise DurableRefusal("refusing state: bad vault key id")
    try:
        private_raw = base64.b64decode(bundle["service_private_keyset_b64"].encode("ascii"), validate=True)
        public_raw = base64.b64decode(bundle["service_public_keyset_b64"].encode("ascii"), validate=True)
    except (ValueError, UnicodeDecodeError, AttributeError, KeyError):
        raise DurableRefusal("refusing state: bad keyset encoding") from None
    _validate_public_keyset(public_raw)
    private_handle = import_service_signer(private_raw)
    rederived = io.BytesIO()
    private_handle.public_keyset_handle().write_no_secret(
        tink.BinaryKeysetWriter(rederived)
    )
    if rederived.getvalue() != public_raw:
        # Stored public pin must correspond to the stored private
        # keyset: a swapped pin can never become a trust root here.
        raise DurableRefusal("refusing state: pin mismatch")
    material["service_private_keyset"] = private_raw
    material["service_public_keyset"] = bundle["service_public_keyset_b64"]
    material["service_signer"] = private_handle.primitive(
        tink.signature.PublicKeySign
    )
    for name in (
        "db_name", "db_host", "db_port", "blob_root", "mode",
        "google_audience", "recovery_rp_id",
    ):
        if bundle.get(name) != expected.get(name):
            raise DurableRefusal("refusing state: deployment mismatch")
    for name in ("google_presenters", "recovery_origins"):
        if list(bundle.get(name) or []) != list(expected.get(name) or []):
            raise DurableRefusal("refusing state: deployment mismatch")
    expected_hmac = expected.get("hmac_key")
    if not isinstance(expected_hmac, bytes) or not hmac.compare_digest(expected_hmac, hmac_key):
        raise DurableRefusal("refusing state: deployment mismatch")
    material["hmac_key"] = hmac_key
    return material


def check_db_empty(engine) -> None:
    """Fresh-DB gate for init: the core identity/capsule/delivery
    tables must hold zero rows (read-only SELECTs, no writes). Any
    content — or any access failure — fails closed."""
    from sqlalchemy import func, select

    from remanence.capsules.delivery_models import RecipientDeliveryState
    from remanence.capsules.models import Capsule
    from remanence.pending.claim_models import PendingDelivery
    from remanence.users.models import User

    try:
        with engine.connect() as connection:
            for model in (User, Capsule, PendingDelivery, RecipientDeliveryState):
                count = connection.execute(
                    select(func.count()).select_from(model)
                ).scalar()
                if count:
                    raise DurableRefusal("refusing init: database not empty")
    except DurableRefusal:
        raise
    except Exception:
        raise DurableRefusal("refusing init: database unreadable") from None
