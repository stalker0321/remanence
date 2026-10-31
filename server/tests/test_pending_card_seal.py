"""ADR-020 threat table, end to end over HTTP on a synthetic database."""

from __future__ import annotations

import base64
import hashlib
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import tink
from cryptography.exceptions import InvalidTag
from sqlalchemy import select
from tink import hybrid, signature, tink_config

pytest_plugins = ("test_session_repository_create",)

import card_seal_client as device
from remanence.pending import domains
from remanence.pending.card_seal import CardSealVault
from remanence.pending.claim_models import AddressProofAttempt, PendingDelivery
from remanence.pending.models import PendingSendRoute
from remanence.pending.providers import MockSmsSender
from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2
from test_pending_m3_http import _build_client
from test_pending_m3_pg import Keyring, _make_bundle, _make_user, _real_aes_keyset

PHONE = "+31612345612"
VAULT_KEY = bytes([0x41] * 32)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


class World:
    def __init__(self, session_factory) -> None:
        self.factory = session_factory
        self.sms = MockSmsSender()
        self.service = Keyring()
        self.service_key_id = uuid.uuid4()
        with session_factory() as session:
            self.sender = _make_user(session, f"snd{uuid.uuid4().hex[:8]}")
            self.sender_ring = Keyring()
            self.sender_bundle = _make_bundle(session, self.sender, self.sender_ring)
            self.recipient = _make_user(session, f"rcp{uuid.uuid4().hex[:8]}")
            self.recipient_ring = Keyring()
            self.recipient_bundle = _make_bundle(session, self.recipient, self.recipient_ring)
            session.commit()
            self.sender_id, self.sender_bundle_id = self.sender.id, self.sender_bundle.id
            self.recipient_id, self.recipient_bundle_id = self.recipient.id, self.recipient_bundle.id
        self.recipient_session = uuid.uuid4()
        self.as_sender = self.client(self.sender_id, uuid.uuid4())
        self.as_recipient = self.client(self.recipient_id, self.recipient_session)

    def client(self, user_id, session_id):
        return _build_client(
            self.factory,
            flags_on=True,
            user_id=user_id,
            session_id=session_id,
            ctx_overrides={
                "vault": CardSealVault(key_32=VAULT_KEY),
                "sms_sender": self.sms,
                "service_key_id": self.service_key_id,
                "service_signer": self.service.ed_private.primitive(signature.PublicKeySign),
            },
        )

    def send_gift(self, phone: str = PHONE, secret: str | None = None) -> dict:
        secret = secret or device.generate_secret()
        capsule_id = uuid.uuid4()
        route = self.as_sender.post(
            "/v2/send-routes",
            json={"capsule_id": str(capsule_id), "destination": {"kind": "phone", "value": phone},
                  "idempotency_key": str(uuid.uuid4())},
        )
        assert route.status_code == 200, route.text
        body = route.json()
        target = uuid.UUID(body["delivery_target_id"])
        salt = bytes(range(32))
        artifact = {
            "blob_id": str(uuid.uuid4()), "kind": 3, "ordinal": 0,
            "ciphertext_size": 7, "ciphertext_sha256_hex": hashlib.sha256(b"sealed!").hexdigest(),
        }
        draft = self.as_sender.post(
            "/v2/pending-deliveries",
            json={"route_id": body["route_id"], "capsule_id": str(capsule_id), "delivery_target_id": str(target),
                  "channel": "sms", "destination": phone, "salt_b64": base64.b64encode(salt).decode(),
                  "claim_expires_at": body["claim_deadline"], "claim_policy_version": 1,
                  "artifacts": [artifact], "idempotency_key": str(uuid.uuid4())},
        )
        assert draft.status_code == 200, draft.text
        delivery_id = uuid.UUID(draft.json()["delivery_id"])
        self.as_sender.app.state.blob_store.put(
            f"pending/{delivery_id}/{artifact['blob_id']}", b"sealed!",
            expected_size=7, expected_sha256=artifact["ciphertext_sha256_hex"],
        )
        keyset = _real_aes_keyset(bytes([0x20] * 32))
        opening = dv2.AddressOpening(normalization_version=1, channel=2, normalized_destination=phone, salt=salt)
        statement = dv2.PendingPublishStatementV2(
            protocol_version=2, capsule_id=capsule_id.bytes, sender_user_id=self.sender_id.bytes,
            sender_key_bundle_id=self.sender_bundle_id.bytes, delivery_target_id=target.bytes,
            address_commitment=domains.domain_digest(
                domains.DELIVERY_ADDRESS_DOMAIN, opening.SerializeToString(deterministic=True)),
            key_commitment=domains.domain_digest(domains.DELIVERY_KEY_DOMAIN, dv2.KeyCommitmentInput(
                version=1, capsule_id=capsule_id.bytes, delivery_target_id=target.bytes,
                capsule_aead_keyset=keyset).SerializeToString(deterministic=True)),
            created_at_epoch_seconds=int(datetime.now(timezone.utc).timestamp()),
            claim_expires_at_epoch_seconds=draft.json()["expires_at"],
            claim_policy_version=1, claim_signing_key_id=self.service_key_id.bytes,
            artifacts=[dv2.ArtifactBindingV2(
                blob_id=uuid.UUID(artifact["blob_id"]).bytes, kind=3, ordinal=0, ciphertext_size=7,
                ciphertext_sha256=bytes.fromhex(artifact["ciphertext_sha256_hex"]))],
        )
        raw = statement.SerializeToString(deterministic=True)
        h = hashlib.sha256(raw).digest()
        seal = device.build_seal(
            secret=secret, keyset=keyset, opening=opening,
            binding=dv2.CardSealAadV1(
                version=1, capsule_id=capsule_id.bytes, delivery_target_id=target.bytes,
                sender_user_id=self.sender_id.bytes, publish_statement_sha256=h,
                address_commitment=statement.address_commitment, key_commitment=statement.key_commitment,
                claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds),
        )
        sent = self.as_sender.post(
            f"/v2/pending-deliveries/{delivery_id}/finalize",
            json={"publish_statement_b64": base64.b64encode(raw).decode(),
                  "publish_signature_69_b64": base64.b64encode(self.sender_ring.sign69(
                      domains.domain_prefix(domains.PENDING_PUBLISH_DOMAIN) + raw)).decode(),
                  "card_seal_b64": base64.b64encode(seal.SerializeToString(deterministic=True)).decode(),
                  "sender_code_sealed_b64": base64.b64encode(b"sealed-to-the-sender").decode(),
                  "idempotency_key": str(uuid.uuid4())},
        )
        assert sent.status_code == 200, sent.text
        assert sent.json()["locator_code"] == ""
        return {"delivery_id": delivery_id, "secret": secret, "statement": raw, "keyset": keyset, "opening": opening}

    def ask_sms(self, phone: str = PHONE, client=None):
        response = (client or self.as_recipient).post(
            "/v2/phone-proofs", json={"phone": phone, "sms_text_version": "v1"})
        assert response.status_code == 200, response.text
        return response

    def sms_code(self, phone: str = PHONE) -> str:
        return self.sms.messages[phone][-1].split(" ", 1)[0]

    def prove(self, phone: str = PHONE) -> tuple[list[dict], str]:
        proof_id = self.ask_sms(phone).json()["proof_id"]
        checked = self.as_recipient.post(f"/v2/phone-proofs/{proof_id}/check", json={"code": self.sms_code(phone)})
        assert checked.status_code == 200, checked.text
        return checked.json()["gifts"], proof_id

    def claim(self, gift: dict, *, envelope: bytes = b"sealed-envelope", proof: bytes | None = None, client=None):
        client = client or self.as_recipient
        delivery_id = gift["delivery_id"]
        options = client.post(f"/v2/pending-deliveries/{delivery_id}/claim/options")
        assert options.status_code == 200, options.text
        body = options.json()
        context_raw = base64.b64decode(body["claim_challenge_b64"])
        context = dv2.ClaimChallengeV1.FromString(context_raw)
        nonce = self.recipient_ring.hpke_decrypt(
            base64.b64decode(body["hpke_challenge_b64"]),
            domains.domain_prefix(domains.CLAIM_CHALLENGE_DOMAIN) + context_raw,
        )
        operation = uuid.uuid4()
        statement_sha = hashlib.sha256(base64.b64decode(gift["publish_statement_b64"])).digest()
        intent = dv2.ClaimIntentV2(
            intent_version=1, operation_id=operation.bytes, capsule_id=context.capsule_id,
            delivery_target_id=context.delivery_target_id, publish_statement_sha256=statement_sha,
            proof_id=uuid.UUID(gift["proof_id"]).bytes, challenge_id=context.challenge_id,
            recipient_user_id=context.recipient_user_id, recipient_key_bundle_id=context.recipient_key_bundle_id,
            session_id=context.session_id, nonce=nonce, nonce_sha256=hashlib.sha256(nonce).digest(),
            bind_alias=True,
        ).SerializeToString(deterministic=True)
        return client.post(
            f"/v2/pending-deliveries/{delivery_id}/claim",
            json={"claim_intent_b64": base64.b64encode(intent).decode(),
                  "claim_intent_signature_69_b64": base64.b64encode(self.recipient_ring.sign69(
                      domains.domain_prefix(domains.CLAIM_INTENT_DOMAIN) + intent)).decode(),
                  "idempotency_key": str(operation),
                  "envelope_b64": base64.b64encode(envelope).decode(),
                  "card_proof_b64": base64.b64encode(proof if proof is not None else bytes(32)).decode()},
        )


@pytest.fixture()
def world(session_factory) -> World:
    return World(session_factory)


def _seal_of(gift: dict) -> dv2.PendingCardSealV1:
    return dv2.PendingCardSealV1.FromString(base64.b64decode(gift["card_seal_b64"]))


def test_full_journey_opens_with_card_and_phone(world) -> None:
    sent = world.send_gift()
    gifts, _ = world.prove()
    assert [g["delivery_id"] for g in gifts] == [str(sent["delivery_id"])]
    plain, proof = device.open_seal(sent["secret"], _seal_of(gifts[0]))
    assert bytes(plain.capsule_aead_keyset) == sent["keyset"]
    claimed = world.claim(gifts[0], proof=proof)
    assert claimed.status_code == 200, claimed.text
    assert base64.b64decode(claimed.json()["envelope_b64"]) == b"sealed-envelope"
    binding = dv2.ClaimBindingV1.FromString(base64.b64decode(claimed.json()["claim_binding_b64"]))
    assert binding.recipient_user_id == world.recipient_id.bytes
    world.service.ed_private.public_keyset_handle().primitive(signature.PublicKeyVerify).verify(
        base64.b64decode(claimed.json()["claim_signature_69_b64"]),
        domains.domain_prefix(domains.CLAIM_BINDING_DOMAIN) + base64.b64decode(claimed.json()["claim_binding_b64"]),
    )
    # Second proof after the claim finds nothing and sends nothing.
    before = len(world.sms.messages[PHONE])
    world.ask_sms()
    assert len(world.sms.messages[PHONE]) == before


def test_code_without_proof_gets_nothing(world) -> None:
    sent = world.send_gift()
    proof_id = world.ask_sms().json()["proof_id"]
    wrong = world.as_recipient.post(f"/v2/phone-proofs/{proof_id}/check", json={"code": "000000"})
    assert wrong.status_code == 400 and wrong.json()["code"] == "ADDRESS_PROOF_REQUIRED"
    assert "card_seal_b64" not in wrong.text
    # No proof, no options: the claim path stays closed too.
    assert world.as_recipient.post(f"/v2/pending-deliveries/{sent['delivery_id']}/claim/options").status_code == 400
    assert world.as_recipient.post("/v2/gift-lookups", json={"code": device.format_code(sent["secret"], PHONE)}).status_code in (404, 422)


def test_proof_without_code_cannot_claim(world) -> None:
    sent = world.send_gift()
    gifts, _ = world.prove()
    with pytest.raises(InvalidTag):
        wrong = "".join(reversed(sent["secret"])) if sent["secret"] != sent["secret"][::-1] else "0000000"
        device.open_seal(wrong, _seal_of(gifts[0]))
    refused = world.claim(gifts[0], envelope=b"garbage", proof=bytes(32))
    assert refused.status_code == 403 and refused.json()["code"] == "CARD_PROOF_REQUIRED"
    # The real recipient is not locked out.
    _, proof = device.open_seal(sent["secret"], _seal_of(gifts[0]))
    assert world.claim(gifts[0], proof=proof).status_code == 200


def test_server_never_receives_key_or_code(world, session_factory) -> None:
    sent = world.send_gift()
    with session_factory() as session:
        row = session.get(PendingDelivery, sent["delivery_id"])
        stored = b"".join(bytes(v or b"") for v in (
            row.vault_ciphertext, row.publish_statement, row.publish_signature, row.sender_code_sealed))
        hint = row.recipient_hint
    plain = CardSealVault(key_32=VAULT_KEY)
    with session_factory() as session:
        row = session.get(PendingDelivery, sent["delivery_id"])
        from remanence.pending.claim import SealedHandle, build_escrow_aad

        statement = dv2.PendingPublishStatementV2.FromString(bytes(row.publish_statement))
        seal = plain.open_seal(
            SealedHandle(row.vault_name, row.vault_key_id, bytes(row.vault_ciphertext), row.vault_binding_hex),
            build_escrow_aad(
                capsule_id=row.capsule_id.bytes, delivery_target_id=row.delivery_target_id.bytes,
                sender_user_id=row.sender_user_id.bytes, publish_statement_sha256=bytes(row.publish_statement_sha256),
                address_commitment=bytes(row.address_commitment), key_commitment=bytes(row.key_commitment),
                claim_policy_version=1, claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds),
        )
    everything = stored + seal.SerializeToString()
    assert sent["keyset"] not in everything
    assert sent["secret"].encode() not in everything
    assert hint == "••••12"


def test_seal_at_rest_needs_the_app_key(world, session_factory) -> None:
    sent = world.send_gift()
    with session_factory() as session:
        row = session.get(PendingDelivery, sent["delivery_id"])
        ciphertext = bytes(row.vault_ciphertext)
        handle = (row.vault_name, row.vault_key_id, ciphertext, row.vault_binding_hex)
        from remanence.pending.claim import SealedHandle, build_escrow_aad

        statement = dv2.PendingPublishStatementV2.FromString(bytes(row.publish_statement))
        aad = build_escrow_aad(
            capsule_id=row.capsule_id.bytes, delivery_target_id=row.delivery_target_id.bytes,
            sender_user_id=row.sender_user_id.bytes, publish_statement_sha256=bytes(row.publish_statement_sha256),
            address_commitment=bytes(row.address_commitment), key_commitment=bytes(row.key_commitment),
            claim_policy_version=1, claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds)
    from remanence.pending.vault import VaultError

    with pytest.raises(VaultError):
        CardSealVault(key_32=bytes(32)).open_seal(SealedHandle(*handle), aad)


def test_claim_deletes_the_seal(world, session_factory) -> None:
    sent = world.send_gift()
    gifts, _ = world.prove()
    _, proof = device.open_seal(sent["secret"], _seal_of(gifts[0]))
    assert world.claim(gifts[0], proof=proof).status_code == 200
    with session_factory() as session:
        row = session.get(PendingDelivery, sent["delivery_id"])
        assert row.state == "CLAIMED"
        assert row.vault_ciphertext is None and row.destination_lookup_tag is None
        assert row.sender_code_sealed is None
        assert session.get(PendingSendRoute, row.route_id).sealed_destination is None


def test_cancel_deletes_the_seal(world, session_factory) -> None:
    sent = world.send_gift()
    assert world.as_sender.post(f"/v2/pending-deliveries/{sent['delivery_id']}/cancel").status_code == 200
    with session_factory() as session:
        row = session.get(PendingDelivery, sent["delivery_id"])
        assert row.vault_ciphertext is None and row.destination_lookup_tag is None
        assert session.get(PendingSendRoute, row.route_id).sealed_destination is None


def test_expiry_deletes_the_seal(world, session_factory) -> None:
    from remanence.pending.publication import expire_card_gifts

    sent = world.send_gift()
    with session_factory() as session:
        assert expire_card_gifts(session, now=datetime.now(timezone.utc) + timedelta(days=181)) >= 1
        session.commit()
    with session_factory() as session:
        row = session.get(PendingDelivery, sent["delivery_id"])
        assert row.state == "EXPIRED"
        assert row.vault_ciphertext is None
        assert session.get(PendingSendRoute, row.route_id).sealed_destination is None


def test_unknown_number_sends_nothing_and_looks_the_same(world) -> None:
    world.send_gift()
    known = world.ask_sms()
    stranger = "+31698765432"
    unknown = world.ask_sms(stranger)
    assert unknown.status_code == known.status_code
    assert set(unknown.json()) == set(known.json())
    assert stranger not in world.sms.messages
    bad = world.as_recipient.post(f"/v2/phone-proofs/{unknown.json()['proof_id']}/check", json={"code": "123456"})
    assert bad.status_code == 400 and bad.json()["code"] == "ADDRESS_PROOF_REQUIRED"


def test_limits_count_the_number_not_the_account(world, session_factory) -> None:
    world.send_gift()
    world.ask_sms()
    assert len(world.sms.messages[PHONE]) == 1
    # A brand new account gets no second message inside the cooldown.
    world.ask_sms(client=world.client(world.sender_id, uuid.uuid4()))
    assert len(world.sms.messages[PHONE]) == 1
    # Older attempts still count toward the daily budget across accounts.
    with session_factory() as session:
        for row in session.scalars(select(AddressProofAttempt)).all():
            row.created_at = datetime.now(timezone.utc) - timedelta(minutes=5)
        template = session.scalars(select(AddressProofAttempt)).first()
        for _ in range(4):
            session.add(AddressProofAttempt(
                id=uuid.uuid4(), delivery_id=template.delivery_id, account_user_id=template.account_user_id,
                session_id=template.session_id, state="EXPIRED", destination_tag=template.destination_tag,
                address_kind="phone", address_lookup_tag=template.address_lookup_tag, otp_hmac=template.otp_hmac,
                attempt_mac=template.attempt_mac, guesses=0, dispatches=1,
                expires_at=datetime.now(timezone.utc) - timedelta(minutes=1),
                created_at=datetime.now(timezone.utc) - timedelta(minutes=5),
            ))
        session.commit()
    world.ask_sms()
    assert len(world.sms.messages[PHONE]) == 1


def test_guess_budget(world) -> None:
    world.send_gift()
    proof_id = world.ask_sms().json()["proof_id"]
    real = world.sms_code()
    wrong = "000000" if real != "000000" else "000001"
    for _ in range(5):
        assert world.as_recipient.post(f"/v2/phone-proofs/{proof_id}/check", json={"code": wrong}).status_code == 400
    assert world.as_recipient.post(f"/v2/phone-proofs/{proof_id}/check", json={"code": real}).status_code == 400


def test_country_outside_the_list_is_refused_to_the_sender(world) -> None:
    refused = world.as_sender.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()), "destination": {"kind": "phone", "value": "+12025550100"},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert refused.status_code == 400 and refused.json()["code"] == "COUNTRY_UNSUPPORTED"


def test_email_destination_is_refused(world) -> None:
    refused = world.as_sender.post(
        "/v2/send-routes",
        json={"capsule_id": str(uuid.uuid4()), "destination": {"kind": "email", "value": "a@b.test"},
              "idempotency_key": str(uuid.uuid4())},
    )
    assert refused.status_code == 400


def test_several_gifts_on_one_number_are_all_found(world) -> None:
    one = world.send_gift()
    two = world.send_gift()
    gifts, _ = world.prove()
    assert {g["delivery_id"] for g in gifts} == {str(one["delivery_id"]), str(two["delivery_id"])}
    by_id = {g["delivery_id"]: g for g in gifts}
    _, proof = device.open_seal(two["secret"], _seal_of(by_id[str(two["delivery_id"])]))
    assert world.claim(by_id[str(two["delivery_id"])], proof=proof).status_code == 200
    # The other gift stays live and claimable with its own card.
    _, proof_one = device.open_seal(one["secret"], _seal_of(by_id[str(one["delivery_id"])]))
    assert world.claim(by_id[str(one["delivery_id"])], proof=proof_one).status_code == 200


def test_code_input_forgives_handwriting() -> None:
    assert device.parse_code("x4t-p9r-k12") == ("X4TP9RK", "12")
    assert device.parse_code(" X4T P9R K12 ") == ("X4TP9RK", "12")
    secret, hint = device.parse_code("0IL-BGS-Z12")
    assert (secret, hint) == ("0118652", "12")
    for bad in ("", "X4T", "X4T-P9R-K1X", "X4T-P9R-K123"):
        with pytest.raises(ValueError):
            device.parse_code(bad)
    assert len(device.ALPHABET) == 28
    assert not set("BGSZILOU") & set(device.ALPHABET)
