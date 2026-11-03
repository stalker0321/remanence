#!/usr/bin/env python3
"""Generates the secrets for first gifts (ADR-020), once. Prints env lines for
server.env and the public pin for the clients. It writes nothing and never
overwrites anything: keep the output, because losing the master secret makes
unclaimed gifts unopenable and the phone numbers unreadable.

  server/.venv/bin/python deploy/pending-secrets.py
"""
import base64
import io
import secrets
import uuid

import tink
from tink import cleartext_keyset_handle, signature, tink_config

tink_config.register()
handle = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
private = io.BytesIO()
cleartext_keyset_handle.write(tink.BinaryKeysetWriter(private), handle)
public = io.BytesIO()
handle.public_keyset_handle().write_no_secret(tink.BinaryKeysetWriter(public))
key_id = str(uuid.uuid4())
public_b64 = base64.b64encode(public.getvalue()).decode()

print("# server.env (secret, mode 600)")
print("REMANENCE_PENDING_PROTOCOL_ENABLED=true")
print("REMANENCE_PENDING_SENDS_ENABLED=true")
print("REMANENCE_PENDING_CLAIMS_ENABLED=true")
print(f"REMANENCE_PENDING_HMAC_SECRET={secrets.token_urlsafe(48)}")
print(f"REMANENCE_PENDING_MASTER_SECRET={secrets.token_urlsafe(48)}")
print(f"REMANENCE_PENDING_SERVICE_KEY_ID={key_id}")
print(f"REMANENCE_PENDING_SERVICE_KEYSET_B64={base64.b64encode(private.getvalue()).decode()}")
print("# SMS, one provider, added by the owner:")
print("# REMANENCE_PENDING_BULKGATE_APPLICATION_ID= and REMANENCE_PENDING_BULKGATE_APPLICATION_TOKEN=")
print("# or REMANENCE_PENDING_TWILIO_ACCOUNT_SID=, REMANENCE_PENDING_TWILIO_AUTH_TOKEN=, REMANENCE_PENDING_TWILIO_SENDER=")
print("# or REMANENCE_PENDING_SMSGATE_USERNAME=, REMANENCE_PENDING_SMSGATE_PASSWORD=, REMANENCE_PENDING_SMSGATE_PASSPHRASE=")
print()
print("# Android build properties (public)")
print("-Premanence.pending.enabled=true")
print(f"-Premanence.pending.serviceKeyId={key_id}")
print(f"-Premanence.pending.serviceKeysetB64={public_b64}")
print()
print("# web app-config.json (public)")
print(f'"pendingServicePin": {{"keyId": "{key_id}", "publicKeysetB64": "{public_b64}"}}')
