"""Production composition for first gifts (ADR-020). All secrets come from the
server environment and stay in process memory; every key is derived from one
master secret, labelled so no two uses can collide."""

from __future__ import annotations

import base64
import hashlib
import hmac
import io
import uuid
from dataclasses import dataclass

from remanence.pending.card_seal import CardSealVault
from remanence.settings import Settings


def _derive(master: bytes, label: str) -> bytes:
    """HKDF-Expand-style single block with a fixed extract key."""
    prk = hmac.new(b"remanence/pending/master/v1", master, hashlib.sha256).digest()
    return hmac.new(prk, label.encode("ascii") + b"\x01", hashlib.sha256).digest()


@dataclass(frozen=True)
class TinkServiceSigner:
    _signer: object

    def __repr__(self) -> str:
        return "TinkServiceSigner(<redacted>)"

    def sign(self, data: bytes) -> bytes:
        return self._signer.sign(data)


def service_signer_from_keyset(keyset_b64: str) -> TinkServiceSigner:
    import tink
    from tink import cleartext_keyset_handle, signature, tink_config

    tink_config.register()
    handle = cleartext_keyset_handle.read(tink.BinaryKeysetReader(base64.b64decode(keyset_b64, validate=True)))
    return TinkServiceSigner(handle.primitive(signature.PublicKeySign))


def service_public_keyset_b64(keyset_b64: str) -> str:
    """The pin that clients embed. Printed by the provisioning script."""
    import tink
    from tink import cleartext_keyset_handle, tink_config

    tink_config.register()
    handle = cleartext_keyset_handle.read(tink.BinaryKeysetReader(base64.b64decode(keyset_b64, validate=True)))
    buffer = io.BytesIO()
    handle.public_keyset_handle().write_no_secret(tink.BinaryKeysetWriter(buffer))
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def build_production_ctx(settings: Settings) -> dict:
    from remanence.pending.sms_bulkgate import BulkGateSmsSender
    from remanence.pending.sms_twilio import TwilioSmsSender

    master = settings.pending_master_secret.get_secret_value().encode("utf-8")
    sms_sender = None
    if settings.pending_bulkgate_application_id and settings.pending_bulkgate_application_token:
        sms_sender = BulkGateSmsSender(
            application_id=settings.pending_bulkgate_application_id,
            application_token=settings.pending_bulkgate_application_token.get_secret_value(),
            sender_name=settings.pending_bulkgate_sender_name,
        )
    elif settings.pending_twilio_account_sid and settings.pending_twilio_auth_token and settings.pending_twilio_sender:
        sms_sender = TwilioSmsSender(
            account_sid=settings.pending_twilio_account_sid,
            auth_token=settings.pending_twilio_auth_token.get_secret_value(),
            sender=settings.pending_twilio_sender,
        )
    return {
        "hmac_key": settings.pending_hmac_secret.get_secret_value().encode(),
        "destination_key": _derive(master, "destination"),
        "otp_key": _derive(master, "otp"),
        "proof_mac_key": _derive(master, "proof-mac"),
        "evidence_key": _derive(master, "evidence"),
        "vault": CardSealVault(key_32=_derive(master, "card-seal")),
        "service_key_id": uuid.UUID(settings.pending_service_key_id),
        "service_signer": service_signer_from_keyset(settings.pending_service_keyset_b64.get_secret_value()),
        "sms_sender": sms_sender,
    }
