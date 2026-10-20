"""Synthetic real-signed publishers shared by T373 PG tests."""

import uuid
from dataclasses import dataclass
from datetime import timedelta

from remanence.capsules.finalize_service import CapsuleFinalizeService
from remanence.capsules.models import Capsule
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.publication import create_draft, finalize_delivery
from remanence.pending.routing import decide_for_send, freeze_route
from remanence.send_access.policy import SendAccessMode
from remanence.send_access.service import provision
from remanence.storage import LocalFileBlobStore

from test_capsule_finalize_service import (
    _add_draft, _envelope, _payloads, _protocol_blobs, _sign, _statement_bytes, _store_all,
)
from test_pending_m3_pg import (
    ADDRESS, DEST_KEY, EVIDENCE_KEY, HMAC_KEY, NOW, Keyring, _make_bundle, _make_user,
)
from test_publication_deadlines import pending_request


@dataclass
class Actor:
    user: object
    bundle: object
    ring: object


@dataclass
class Source:
    arm: str
    id: uuid.UUID
    capsule_id: uuid.UUID
    sender_id: uuid.UUID
    kwargs: dict
    store: object = None
    setup: dict | None = None

    def row(self, session):
        return session.get(Capsule if self.arm == "v1" else PendingDelivery, self.id)


def actor(session, *, provisioned=True):
    ring = Keyring()
    user = _make_user(session, f"t373{uuid.uuid4().hex[:12]}")
    bundle = _make_bundle(session, user, ring)
    if provisioned:
        provision(session, user.id, now=NOW)
    return Actor(user, bundle, ring)


def source(session, tmp_path, arm, *, sender, recipient=None, capsule_id=None):
    if arm == "v1":
        recipient = recipient or actor(session)
        payloads = _payloads()
        capsule = _add_draft(
            session, sender=sender.user, sender_bundle=sender.bundle,
            recipient=recipient.user, recipient_bundle=recipient.bundle, payloads=payloads,
        )
        capsule.created_at = NOW
        capsule.draft_expires_at = NOW + timedelta(days=7)
        store = LocalFileBlobStore(tmp_path / "blobs")
        _store_all(session, store, capsule, payloads)
        raw = _statement_bytes(capsule, _protocol_blobs(session, capsule))
        return Source("v1", capsule.id, capsule.id, sender.user.id, dict(
            authenticated_sender_user_id=sender.user.id, capsule_id=capsule.id,
            sender_key_bundle_id=sender.bundle.id, statement=raw,
            signature=_sign(sender.ring.ed_private, raw), envelope=_envelope(recipient.bundle.id),
        ), store=store)
    address = ADDRESS
    decision = decide_for_send(
        session, destination_kind="email", normalized_destination=address,
        active_bundle_id=None, hmac_key=HMAC_KEY, evidence_key=EVIDENCE_KEY,
        sender_user_id=sender.user.id,
    )
    route = freeze_route(
        session, sender_user_id=sender.user.id, capsule_id=capsule_id or uuid.uuid4(),
        destination_kind="email", normalized_destination=address,
        destination_key_32=DEST_KEY, destination_key_mode="test", hmac_key=HMAC_KEY,
        decision=decision, now=NOW, pending_sends_enabled=True,
    )
    declarations = [dict(blob_id=str(uuid.uuid4()), kind=3, ordinal=0,
                         ciphertext_size=505, ciphertext_sha256_hex="55" * 32)]
    delivery = create_draft(
        session, sender_user_id=sender.user.id, capsule_id=route.capsule_id,
        delivery_target_id=route.delivery_target_id, route_id=route.id, channel="email",
        claim_expires_at=NOW + timedelta(days=180), declarations=declarations,
        now=NOW, pending_sends_enabled=True,
    )
    setup = dict(delivery=delivery, route=route, declarations=declarations,
                 sender=sender.user, sender_bundle=sender.bundle, sender_ring=sender.ring)
    return Source("v2", delivery.id, delivery.capsule_id, sender.user.id,
                  pending_request(setup), setup=setup)


def publish(session, item, *, mode=SendAccessMode.ENFORCE, clock=lambda: NOW, **extra):
    kwargs = dict(item.kwargs, now=NOW, clock=clock, send_access_mode=mode, **extra)
    if item.arm == "v1":
        return CapsuleFinalizeService(session, item.store).finalize(**kwargs)
    return finalize_delivery(session, **kwargs)
