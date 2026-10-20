"""T403 real isolated PostgreSQL / RSA Google / ES256 / Tink / ADR-011 proof.

Only certificate transport is synthetic. No successful authentication verifier
or record validator is mocked. Fault hooks fail writes, never authorize them.
"""
import copy
import hashlib
import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import pytest
import tink
from tink import hybrid, signature, tink_config, proto_keyset_format
from sqlalchemy import event, func, select, text

from test_me_endpoint import me_env, _seed
from test_google_auth import google_env, options, verify, registration
from test_passkey_recovery import Authenticator, record, b64, unb64
from remanence.auth.google_models import GoogleIdentity
from remanence.auth.models import AuthSession
from remanence.recovery.models import RecoveryCredential, RecoveryRecord
from remanence.registration_setup.models import RegistrationReservation as Reservation, RegistrationCredential as Credential, RegistrationChallenge as Challenge, RegistrationPolicy
from remanence.registration_setup.service import AtomicRegistration
from remanence.send_access.models import AccountSendAccess
from remanence.users.key_models import UserKeyBundle
from remanence.users.models import User

PERMANENT = (User, GoogleIdentity, UserKeyBundle, RecoveryCredential, RecoveryRecord, AuthSession, AccountSendAccess)


@pytest.fixture
def atomic(google_env):
    client, factory, token = google_env
    client.app.state.settings.registration_atomic_required = True
    client.app.state.settings.registration_atomic_admission_enabled = True
    now = [datetime.now(timezone.utc)]
    client.app.state.registration_clock = lambda: now[0]
    return client, factory, token, now


def proof(env, subject="synthetic-subject", **claims):
    client, _, token, _ = env
    begin = options(client)
    response = client.post("/v1/auth/google/verify", json={"flow": begin["flow"], "nonce": begin["nonce"],
        "id_token": token(begin, sub=subject, email=subject + "@example.com", **claims), "onboarding_version": 1})
    assert response.headers["cache-control"] == "no-store"
    return response


def reserve(env, subject="synthetic-subject"):
    reply = proof(env, subject)
    assert reply.status_code == 200, reply.status_code
    result = reply.json()
    assert result["status"] == "SETUP_REQUIRED"
    return result


def headers(a):
    return {"Authorization": "Bearer " + a["setup_token"]}


def url(a, suffix=""):
    return "/v2/registration/reservations/" + a["reservation"]["id"] + ("/" + suffix if suffix else "")


def post(env, a, suffix, payload=None, expected=200):
    body = {"epoch": a["reservation"]["epoch"], **(payload or {})}
    response = env[0].post(url(a, suffix), json=body, headers=headers(a))
    assert response.status_code == expected, (suffix, response.status_code, response.json().get("reason"))
    assert response.headers["cache-control"] == "no-store"
    return response.json()


def counts(factory):
    with factory() as db:
        return [db.scalar(select(func.count()).select_from(model)) for model in PERMANENT]


def register_credential(env, a, auth=None):
    auth = auth or Authenticator(a["reservation"]["account_id"])
    begin = post(env, a, "credential/options")
    assert unb64(begin["options"]["user"]["id"]) == uuid.UUID(auth.user).bytes
    verified = post(env, a, "credential/verify", {"flow": begin["flow"], "response": auth.response(begin["options"], True)})
    assert verified == dict(credential_id=b64(auth.id), registered=True, recoverable=False)
    return auth


def grant(env, a, auth):
    begin = post(env, a, "setup/options", {"credential_id": b64(auth.id)})
    assert [x["id"] for x in begin["options"]["allowCredentials"]] == [b64(auth.id)]
    return post(env, a, "setup/verify", {"flow": begin["flow"], "response": auth.response(begin["options"])})["final_authorization"]


def candidate(env, subject="synthetic-subject", handle="atomicuser"):
    a = reserve(env, subject)
    auth = register_credential(env, a)
    final_authorization = grant(env, a, auth)
    tink_config.register()
    enc = tink.new_keyset_handle(hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM)
    sign = tink.new_keyset_handle(signature.signature_key_templates.ED25519)
    public = proto_keyset_format.serialize_without_secret(enc.public_keyset_handle())
    bundle = str(uuid.UUID(bytes=hashlib.md5(public).digest(), version=3))
    material = dict(auth=auth, bundle=bundle, enc=enc, sign=sign, prf=os.urandom(32), code=os.urandom(32))
    body = dict(epoch=a["reservation"]["epoch"], operation_id=str(uuid.uuid4()), final_authorization=final_authorization,
        handle=handle, key_bundle=dict(key_bundle_id=bundle, suite="HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocol_version=1, encryption_public_keyset=b64(public),
        signing_public_keyset=b64(proto_keyset_format.serialize_without_secret(sign.public_keyset_handle()))), record=record(material))
    return a, body, material


def final(env, a, body):
    return env[0].post(url(a, "finalize"), json=body, headers=headers(a))


def test_full_atomic_readback_exact_replay_expired_grant_no_extra_session(atomic):
    client, factory, _, now = atomic
    a, body, material = candidate(atomic)
    assert counts(factory) == [0] * 7
    assert client.get("/v1/me", headers=headers(a)).status_code == 401
    assert client.get(url(a), headers=headers(a)).json()["reservation"]["credential_ids"] == [b64(material["auth"].id)]
    reply = final(atomic, a, body)
    assert reply.status_code == 201, reply.status_code
    login = reply.json()["login"]
    assert login["user"]["user_id"] == a["reservation"]["account_id"]
    assert counts(factory) == [1] * 7
    access = {"Authorization": "Bearer " + login["access_token"]}
    assert client.get("/v1/recovery/status", headers=access).json() == {"state": "STORED", "credential_id": body["record"]["credential_id"]}
    stored = client.get("/v1/recovery/record", headers=access).json()
    assert stored["record"] == body["record"]
    for field in ("encryption_public_keyset", "signing_public_keyset"):
        assert stored[field] == body["key_bundle"][field]
    # Unwrap ACTUAL permanent readback, not the submitted object.
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives import hashes
    from remanence.api.recovery import context, EncryptedRecord
    r = EncryptedRecord(**stored["record"])
    ctx = context(r).encode()
    kek = HKDF(hashes.SHA256(), 32, unb64(r.salt), ctx + b"\nprf-ark").derive(material["prf"])
    wrapped = unb64(r.prf_ark)
    ark = AESGCM(kek).decrypt(wrapped[:12], wrapped[12:], ctx + b"\nprf-ark")
    package = unb64(r.package_sealed)
    clear = AESGCM(ark).decrypt(package[:12], package[12:], ctx + b"\npackage")
    assert clear[:4] == b"RPK1"
    from tink import _secret_key_access
    enc_size = int.from_bytes(clear[4:8], "big")
    private_enc = clear[8:8 + enc_size]
    sign_size = int.from_bytes(clear[8 + enc_size:12 + enc_size], "big")
    private_sign = clear[12 + enc_size:]
    assert len(private_sign) == sign_size
    for private, field in ((private_enc, "encryption_public_keyset"), (private_sign, "signing_public_keyset")):
        restored = proto_keyset_format.parse(private, _secret_key_access.TOKEN)
        assert b64(proto_keyset_format.serialize_without_secret(restored.public_keyset_handle())) == stored[field]
    now[0] += timedelta(seconds=301)
    replay = final(atomic, a, body)
    assert replay.status_code == 200
    assert replay.json() == {"status": "ACTIVE", "outcome": reply.json()["outcome"], "reauthenticate": True}
    assert counts(factory) == [1] * 7
    assert client.get(url(a), headers=access).json()["reservation"]["state"] == "ACTIVE"
    with factory() as db:
        assert db.scalar(select(func.count()).select_from(Challenge)) == 0
        assert db.scalar(select(func.count()).select_from(Credential)) == 0


def test_every_accepted_intent_field_is_compared(atomic):
    a, body, _ = candidate(atomic)
    assert final(atomic, a, body).status_code == 201
    mutations = [("handle", "otheruser"), ("final_authorization", "rgf_" + b64(os.urandom(32)))]
    for field in body["key_bundle"]:
        if field in ("suite", "protocol_version"): continue  # invalid alternatives reject at schema, below
        template = hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM if field == "encryption_public_keyset" else signature.signature_key_templates.ED25519
        value = str(uuid.uuid4()) if field == "key_bundle_id" else b64(proto_keyset_format.serialize_without_secret(tink.new_keyset_handle(template).public_keyset_handle()))
        mutations.append(("key_bundle." + field, value))
    for field in body["record"]:
        if field in ("version", "generation"): continue
        value = str(uuid.uuid4()) if field in ("account_id", "bundle_id") else b64(os.urandom(len(unb64(body["record"][field]))))
        mutations.append(("record." + field, value))
    for path, value in mutations:
        changed = copy.deepcopy(body)
        if "." in path:
            first, second = path.split("."); changed[first][second] = value
        else: changed[path] = value
        response = final(atomic, a, changed)
        assert response.status_code == 409, (path, response.status_code)
        assert response.json()["reason"] == "INTENT_CONFLICT", path
    for area, field, value in (("key_bundle", "suite", "unknown"), ("key_bundle", "protocol_version", 2),
                               ("record", "generation", 2), ("record", "version", "unknown")):
        changed = copy.deepcopy(body); changed[area][field] = value
        assert final(atomic, a, changed).status_code == 422
    changed = copy.deepcopy(body); changed["operation_id"] = str(uuid.uuid4())
    assert final(atomic, a, changed).json()["reason"] == "ALREADY_ACTIVE"
    assert counts(atomic[1]) == [1] * 7


@pytest.mark.parametrize("table", ["users", "account_send_access", "user_key_bundles", "auth_sessions",
    "google_identities", "recovery_credentials", "recovery_records", "registration_reservations"])
def test_fault_at_each_permanent_row_rolls_back_all(atomic, table):
    a, body, _ = candidate(atomic)
    engine = atomic[1].kw["bind"]
    hit = []
    def fault(conn, cursor, statement, parameters, context, executemany):
        normalized = statement.lower()
        if ((table != "registration_reservations" and normalized.startswith("insert into " + table + " ")) or
            (table == "registration_reservations" and normalized.startswith("update registration_reservations set state="))):
            hit.append(True)
            raise RuntimeError("synthetic write failure")
    event.listen(engine, "before_cursor_execute", fault)
    try:
        assert final(atomic, a, body).status_code == 503
    finally:
        event.remove(engine, "before_cursor_execute", fault)
    assert hit, table
    assert counts(atomic[1]) == [0] * 7
    assert atomic[0].get(url(a), headers=headers(a)).json()["reservation"]["state"] == "RESERVED"
    assert final(atomic, a, body).status_code == 201


def test_handle_conflict_preserves_grant_and_exact_candidate(atomic):
    _seed(atomic[0], handle="taken")
    before = counts(atomic[1])
    a, body, _ = candidate(atomic, handle="taken")
    reply = final(atomic, a, body)
    assert reply.status_code == 409 and reply.json()["reason"] == "HANDLE_TAKEN"
    assert counts(atomic[1]) == before
    with atomic[1]() as db:
        grant_row = db.scalar(select(Challenge).where(Challenge.purpose == "FINAL"))
        deadline = grant_row.expires_at
        assert grant_row.consumed_at is None
    corrected = copy.deepcopy(body); corrected["handle"] = "available"; corrected["operation_id"] = str(uuid.uuid4())
    assert final(atomic, a, corrected).status_code == 201
    with atomic[1]() as db:
        assert db.get(RecoveryRecord, uuid.UUID(a["reservation"]["account_id"])).payload == body["record"]
    assert deadline == atomic[3][0] + timedelta(seconds=300)


@pytest.mark.parametrize("field", ["account_id", "bundle_id", "credential_id", "signature", "encryption_public_keyset"])
def test_bad_binding_or_signature_burns_grant_without_account(atomic, field):
    a, body, _ = candidate(atomic)
    bad = copy.deepcopy(body)
    if field == "encryption_public_keyset":
        bad["key_bundle"][field] = b64(proto_keyset_format.serialize_without_secret(tink.new_keyset_handle(
            hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM).public_keyset_handle()))
    elif field in ("account_id", "bundle_id"): bad["record"][field] = str(uuid.uuid4())
    else: bad["record"][field] = b64(os.urandom(len(unb64(body["record"][field]))))
    assert final(atomic, a, bad).status_code == 400
    assert final(atomic, a, body).status_code == 400
    assert counts(atomic[1]) == [0] * 7


@pytest.mark.parametrize("failure", ["origin", "rp", "uv", "handle", "signature", "challenge", "counter"])
def test_real_assertion_rejects_and_burns_only_addressed_challenge(atomic, failure):
    a = reserve(atomic); auth = register_credential(atomic, a)
    grant(atomic, a, auth)
    begin = post(atomic, a, "setup/options", {"credential_id": b64(auth.id)})
    kwargs = {"origin": "https://evil.invalid"} if failure == "origin" else {"rp": "evil.invalid"} if failure == "rp" else {"uv": False} if failure == "uv" else {"handle": uuid.uuid4()} if failure == "handle" else {}
    if failure == "counter": auth.counter = 0
    used = copy.deepcopy(begin["options"])
    if failure == "challenge": used["challenge"] = b64(os.urandom(32))
    bad = auth.response(used, **kwargs)
    if failure == "signature": bad["response"]["signature"] = b64(os.urandom(70))
    post(atomic, a, "setup/verify", {"flow": begin["flow"], "response": bad}, 400)
    post(atomic, a, "setup/verify", {"flow": begin["flow"], "response": auth.response(begin["options"])}, 400)
    with atomic[1]() as db:
        assert db.get(Credential, b64(auth.id)).counter == 1
        assert db.scalar(select(func.count()).select_from(Challenge).where(Challenge.purpose == "FINAL")) == 1
    assert counts(atomic[1]) == [0] * 7


def test_takeover_expired_capability_and_cross_owner_scope(atomic):
    a, body, material = candidate(atomic)
    other = reserve(atomic, "other-subject")
    assert atomic[0].get(url(a), headers=headers(other)).status_code == 404
    post(atomic, other, "setup/options", {"credential_id": b64(material["auth"].id)}, 400)
    old_deadline = a["reservation"]["expires_at"]
    atomic[3][0] += timedelta(seconds=601)
    assert final(atomic, a, body).status_code == 401
    fresh = reserve(atomic)
    assert fresh["reservation"]["id"] == a["reservation"]["id"]
    assert fresh["reservation"]["expires_at"] == old_deadline
    assert fresh["reservation"]["epoch"] == a["reservation"]["epoch"] + 1
    assert fresh["reservation"]["credential_ids"] == [b64(material["auth"].id)]
    assert final(atomic, a, body).status_code == 401
    assert final(atomic, fresh, body).json()["reason"] == "STALE_SETUP"
    body["epoch"] = fresh["reservation"]["epoch"]
    assert final(atomic, fresh, body).status_code == 400
    body["final_authorization"] = grant(atomic, fresh, material["auth"])
    assert final(atomic, fresh, body).status_code == 201


def test_cancel_expiry_restart_and_active_are_terminally_distinct(atomic):
    a, body, _ = candidate(atomic)
    cancelled = post(atomic, a, "cancel")["reservation"]
    assert cancelled["state"] == "CANCELLED" and cancelled["credential_ids"] == []
    assert post(atomic, a, "cancel")["reservation"] == cancelled
    assert final(atomic, a, body).status_code == 410
    post(atomic, a, "restart", expected=401)
    terminal = reserve(atomic)
    assert terminal["reservation"]["account_id"] == a["reservation"]["account_id"]
    successor = post(atomic, terminal, "restart")
    assert successor["reservation"]["account_id"] != a["reservation"]["account_id"]
    assert successor["reservation"]["credential_ids"] == []
    repeated = post(atomic, terminal, "restart")
    assert repeated["reservation"]["id"] == successor["reservation"]["id"]
    assert "setup_token" not in repeated
    atomic[3][0] += timedelta(days=1, seconds=1)
    expired = reserve(atomic)
    assert expired["reservation"]["state"] == "EXPIRED"
    assert expired["reservation"]["id"] == successor["reservation"]["id"]
    assert counts(atomic[1]) == [0] * 7
    post(atomic, expired, "credential/options", expected=410)


def test_leave_resume_stop_admission_and_durable_legacy_latch(atomic):
    a = reserve(atomic)
    post(atomic, a, "leave")
    assert atomic[0].get(url(a), headers=headers(a)).status_code == 401
    cfg = atomic[0].app.state.settings
    cfg.registration_atomic_admission_enabled = False
    fresh = reserve(atomic)
    assert fresh["reservation"]["id"] == a["reservation"]["id"]
    assert proof(atomic, "new-subject").status_code == 403
    post(atomic, fresh, "cancel")
    terminal = reserve(atomic)
    post(atomic, terminal, "restart", expected=403)
    cfg.registration_atomic_required = False
    begin = options(atomic[0])
    assert verify(atomic[0], begin, atomic[2](begin)).status_code == 426
    with atomic[1]() as db:
        assert db.get(RegistrationPolicy, 1).required is True


def test_old_register_ticket_cutover_and_existing_login_unchanged(google_env):
    client, factory, token = google_env
    begin = options(client); ticket = verify(client, begin, token(begin)).json()
    cfg = client.app.state.settings
    cfg.registration_atomic_required = True; cfg.registration_atomic_admission_enabled = True
    assert client.post("/v1/auth/google/register", json=registration(ticket)).status_code == 426
    cfg.registration_atomic_required = False; cfg.registration_atomic_admission_enabled = False
    assert client.post("/v1/auth/google/register", json=registration(ticket)).status_code == 426
    assert counts(factory) == [0] * 7


def test_after_commit_loss_fresh_google_and_receipt_retention_never_regenerate(atomic):
    a, body, _ = candidate(atomic)
    accepted = final(atomic, a, body)
    assert accepted.status_code == 201  # discard reply: simulate client's lost response
    atomic[3][0] += timedelta(days=31)
    again = proof(atomic)
    assert again.status_code == 200 and again.json()["status"] == "SIGNED_IN"
    login = again.json()["login"]
    assert login["user"]["user_id"] == a["reservation"]["account_id"]
    access = {"Authorization": "Bearer " + login["access_token"]}
    status = atomic[0].get(url(a), headers=access).json()["reservation"]
    assert status["state"] == "ACTIVE" and status["outcome"] is None
    assert counts(atomic[1]) == [1, 1, 1, 1, 1, 2, 1]
    assert atomic[0].get("/v1/recovery/record", headers=access).json()["record"] == body["record"]


def test_budgets_strict_scalars_and_private_dto_rejection(atomic):
    a = reserve(atomic)
    for value in (True, "1", 1.0, 0, -1):
        post(atomic, a, "credential/options", {"epoch": value}, 422)
    post(atomic, a, "credential/options", {"prf": "secret"}, 422)
    for _ in range(8): post(atomic, a, "credential/options")
    post(atomic, a, "credential/options", expected=429)
    atomic[3][0] += timedelta(seconds=301)
    for _ in range(4): register_credential(atomic, a)
    post(atomic, a, "credential/options", expected=429)
    with atomic[1]() as db:
        assert db.scalar(select(func.count()).select_from(Challenge)) <= 8
        assert db.scalar(select(func.count()).select_from(Credential)) == 4
    for value in (True, "1", 1.0, None, 2):
        begin = options(atomic[0])
        response = atomic[0].post("/v1/auth/google/verify", json={"flow": begin["flow"], "nonce": begin["nonce"],
            "id_token": atomic[2](begin), "onboarding_version": value})
        assert response.status_code == 422 and response.headers["cache-control"] == "no-store"


def test_concurrent_exact_and_changed_final_and_one_subject_reservation(atomic):
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: proof(atomic), range(2)))
    assert all(r.status_code == 200 for r in responses)
    assert len({r.json()["reservation"]["id"] for r in responses}) == 1
    a, body, _ = candidate(atomic)
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(lambda _: final(atomic, a, body), range(2)))
    assert sorted(r.status_code for r in replies) == [200, 201]
    assert counts(atomic[1]) == [1] * 7


@pytest.mark.parametrize("contender", ["takeover", "cancel", "status"])
def test_inflight_commit_fences_takeover_cancel_and_status(atomic, monkeypatch, contender):
    a, body, _ = candidate(atomic)
    reached, release, started = threading.Event(), threading.Event(), threading.Event()
    original = AtomicRegistration._login
    import remanence.registration_setup.service as service_module
    original_lock = service_module.lock_subject
    waiting = threading.Event()
    def observe_lock(db, subject):
        if reached.is_set(): waiting.set()
        return original_lock(db, subject)
    monkeypatch.setattr(service_module, "lock_subject", observe_lock)
    monkeypatch.setattr("remanence.api.google_auth.lock_subject", observe_lock)
    def pause(self, result, bundle):
        reached.set()
        assert release.wait(8)
        return original(self, result, bundle)
    monkeypatch.setattr(AtomicRegistration, "_login", pause)
    def compete():
        started.set()
        if contender == "takeover": return proof(atomic)
        if contender == "cancel": return atomic[0].post(url(a, "cancel"), headers=headers(a), json={"epoch": a["reservation"]["epoch"]})
        return atomic[0].get(url(a), headers=headers(a))
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(final, atomic, a, body)
        try:
            assert reached.wait(8)
            # Uncommitted permanent rows are not visible from a separate PG connection.
            assert counts(atomic[1]) == [0] * 7
            second = pool.submit(compete)
            assert started.wait(3)
            assert waiting.wait(5)
            assert not second.done()
        finally:
            release.set()
        assert first.result(timeout=8).status_code == 201
        reply = second.result(timeout=8)
    if contender == "takeover":
        assert reply.status_code == 200 and reply.json()["status"] == "SIGNED_IN"
    elif contender == "cancel":
        assert reply.status_code == 409 and reply.json()["reason"] == "ALREADY_ACTIVE"
    else:
        assert reply.status_code == 200 and reply.json()["reservation"]["state"] == "ACTIVE"


def test_concurrent_shared_bundle_and_credential_fail_closed(atomic):
    first, body, material = candidate(atomic)
    second = reserve(atomic, "second-subject")
    duplicate = Authenticator(second["reservation"]["account_id"])
    duplicate.id, duplicate.key = material["auth"].id, material["auth"].key
    begin = post(atomic, second, "credential/options")
    post(atomic, second, "credential/verify", {"flow": begin["flow"], "response": duplicate.response(begin["options"], True)}, 409)
    b, other, other_material = candidate(atomic, "third-subject", "otheruser")
    other["key_bundle"] = copy.deepcopy(body["key_bundle"])
    other_material.update(enc=material["enc"], sign=material["sign"], bundle=material["bundle"])
    other["record"] = record(other_material)
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = [pool.submit(final, atomic, first, body), pool.submit(final, atomic, b, other)]
        codes = [x.result(timeout=8).status_code for x in outcomes]
    assert sorted(codes) == [201, 409]
    assert counts(atomic[1]) == [1] * 7
