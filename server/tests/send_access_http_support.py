"""Actual app/bearer authentication; synthetic keys, custody and blob metadata."""

import base64
from datetime import timedelta
from types import SimpleNamespace

from fastapi.testclient import TestClient

from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token
from remanence.main import create_app
from remanence.send_access.policy import SendAccessMode
from remanence.settings import AppMode, Settings
from send_access_support import NOW
from test_capsule_finalize_endpoint import _finalize_payload
from test_pending_m3_pg import DEST_KEY, HMAC_KEY, LOCATOR_CODE_KEY


def bearer(session, account_id):
    token = generate_access_token()
    row = AuthSessionRepository(session).create(
        user_id=account_id, access_token_hash=hash_opaque_token(token),
        refresh_token_hash=hash_opaque_token(generate_refresh_token()),
        access_expires_at=NOW + timedelta(days=365), refresh_expires_at=NOW + timedelta(days=366),
    )
    return {"Authorization": f"Bearer {token}"}, row.id


def publication_client(factory, item, monkeypatch, *, mode=SendAccessMode.ENFORCE):
    pending = item.arm == "v2"
    settings = Settings(mode=AppMode.TEST, send_access_mode=mode,
                        pending_protocol_enabled=pending, pending_sends_enabled=pending,
                        pending_hmac_secret=HMAC_KEY.decode("ascii") if pending else None)
    ctx = None if not pending else dict(
        hmac_key=HMAC_KEY, destination_key=DEST_KEY, locator_code_key=LOCATOR_CODE_KEY,
        vault=item.kwargs["vault"], service_key_id=item.kwargs["expected_service_key_id"],
    )
    # v1 uses actual fixture files; v2 storage metadata is a deliberate fake.
    store = item.store if not pending else SimpleNamespace(
        stat=lambda key: SimpleNamespace(size=505, sha256_hex="55" * 32))
    app = create_app(settings, session_factory=factory, blob_store=store, pending_ctx=ctx)
    from remanence.capsules.finalize_service import CapsuleFinalizeService
    from remanence.pending import publication
    original_v1, original_v2 = CapsuleFinalizeService.finalize, publication.finalize_delivery

    def v1(self, **kwargs):
        return original_v1(self, **dict(kwargs, now=NOW, clock=lambda: NOW))

    def v2(session, **kwargs):
        return original_v2(session, **dict(kwargs, now=NOW, clock=lambda: NOW))

    monkeypatch.setattr(CapsuleFinalizeService, "finalize", v1)
    monkeypatch.setattr(publication, "finalize_delivery", v2)
    return TestClient(app, raise_server_exceptions=False)


def post(client, item, headers):
    kw = item.kwargs
    if item.arm == "v1":
        payload = _finalize_payload(
            statement=kw["statement"], signature=kw["signature"],
            sender_key_bundle_id=kw["sender_key_bundle_id"],
            recipient_key_bundle_id=kw["envelope"].recipient_key_bundle_id,
            ciphertext=kw["envelope"].ciphertext,
        )
        path = f"/v1/capsules/{item.id}/finalize"
    else:
        encode = lambda value: base64.b64encode(value).decode("ascii")
        payload = dict(publish_statement_b64=encode(kw["statement_raw"]),
                       publish_signature_69_b64=encode(kw["signature_69"]),
                       escrow_payload_b64=encode(kw["escrow_payload_raw"]),
                       idempotency_key=str(kw["operation_id"]))
        path = f"/v2/pending-deliveries/{item.id}/finalize"
    return client.post(path, json=payload, headers=headers)
