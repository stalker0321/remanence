"""Real PostgreSQL + ES256 WebAuthn + Tink identity proofs, no verifier mocks."""
import copy
import hashlib
import json
import os
import struct
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import cbor2
import pytest
import tink
from tink import tink_config, signature, hybrid, proto_keyset_format, _secret_key_access
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from sqlalchemy import select

from test_me_endpoint import me_env
from remanence.api.recovery import b64, unb64, context, manifest, EncryptedRecord, PRF_INPUT
from remanence.auth.models import AuthSession
from remanence.recovery.models import RecoveryRecord, RecoveryChallenge
from remanence.settings import Settings, AppMode

RP = "remanence.hryshyn.dev"
ORIGIN = "https://remanence.hryshyn.dev"


def test_setup_status_is_owner_bound_and_supports_resume(env):
    client, factory = env; a = seed(client); other = seed(client, "b")
    assert client.get("/v1/recovery/status").status_code == 401
    assert client.get("/v1/recovery/status", headers=a["headers"]).json()["state"] == "NOT_STARTED"
    id = register(client, a)
    assert client.get("/v1/recovery/status", headers=a["headers"]).json() == {"state": "PENDING", "credential_id": id}
    assert client.get("/v1/recovery/status", headers=other["headers"]).json()["state"] == "NOT_STARTED"
    r = record(a); flow = setup(client, a)
    post(client, "record", {"flow": flow, "record": r}, a["headers"])
    assert client.get("/v1/recovery/status", headers=a["headers"]).json() == {"state": "STORED", "credential_id": id}
    # Resume verification uses the same credential and unchanged encrypted package.
    setup(client, a)
    assert client.get("/v1/recovery/record", headers=a["headers"]).json()["record"] == r


class Authenticator:
    def __init__(self, user):
        self.user = user; self.id = os.urandom(32); self.key = ec.generate_private_key(ec.SECP256R1()); self.counter = 0

    def response(self, options, create=False, origin=ORIGIN, uv=True, handle=None, rp=RP):
        data = json.dumps({"type": "webauthn.create" if create else "webauthn.get", "challenge": options["challenge"], "origin": origin, "crossOrigin": False}, separators=(",", ":")).encode()
        self.counter += not create
        flags = 1 | (4 if uv else 0) | 8 | 16 | (64 if create else 0)
        auth = hashlib.sha256(rp.encode()).digest() + bytes([flags]) + self.counter.to_bytes(4, "big")
        out = {"id": b64(self.id), "rawId": b64(self.id), "type": "public-key", "clientExtensionResults": {}, "response": {"clientDataJSON": b64(data)}}
        if create:
            n = self.key.public_key().public_numbers()
            cose = {1: 2, 3: -7, -1: 1, -2: n.x.to_bytes(32,"big"), -3: n.y.to_bytes(32,"big")}
            auth += bytes(16) + len(self.id).to_bytes(2,"big") + self.id + cbor2.dumps(cose)
            out["response"].update(attestationObject=b64(cbor2.dumps({"fmt": "none", "attStmt": {}, "authData": auth})), transports=["internal"])
        else:
            sig = self.key.sign(auth + hashlib.sha256(data).digest(), ec.ECDSA(hashes.SHA256()))
            out["response"].update(authenticatorData=b64(auth), signature=b64(sig), userHandle=b64((handle or uuid.UUID(self.user)).bytes))
        return out


@pytest.fixture
def env(me_env):
    client, factory = me_env
    client.app.state.settings.recovery_enabled = True
    return client, factory


def seed(client, suffix="a"):
    tink_config.register()
    enc = tink.new_keyset_handle(hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM)
    sign = tink.new_keyset_handle(signature.signature_key_templates.ED25519)
    e = proto_keyset_format.serialize_without_secret(enc.public_keyset_handle())
    s = proto_keyset_format.serialize_without_secret(sign.public_keyset_handle())
    bundle = str(uuid.UUID(bytes=hashlib.md5(e).digest(), version=3))
    body = {"email": f"{suffix}@example.com", "password": "synthetic test password", "handle": f"test{suffix}", "key_bundle": {
        "key_bundle_id": bundle, "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519", "protocol_version": 1,
        "encryption_public_keyset": b64(e), "signing_public_keyset": b64(s)}}
    reply = client.post("/v1/auth/register", json=body)
    assert reply.status_code == 201
    login = reply.json()
    return {"login": login, "headers": {"Authorization": "Bearer " + login["access_token"]}, "bundle": bundle, "enc": enc, "sign": sign,
        "auth": Authenticator(login["user"]["user_id"]), "prf": os.urandom(32), "code": os.urandom(32)}


def post(client, path, body, headers=None, expected=200):
    response = client.post("/v1/recovery/" + path, json=body, headers=headers)
    assert response.status_code == expected, (path, response.status_code) # no payload in failures
    return response.json()


def register(client, a):
    begin = post(client, "register/options", {}, a["headers"])
    reply = post(client, "register/verify", {"flow": begin["flow"], "response": a["auth"].response(begin["options"], True)}, a["headers"])
    assert not reply["recoverable"]
    return reply["credential_id"]


def setup(client, a):
    begin = post(client, "setup/options", {"credential_id": b64(a["auth"].id)}, a["headers"])
    assert unb64(begin["options"]["extensions"]["prf"]["eval"]["first"]) == PRF_INPUT
    return post(client, "setup/verify", {"flow": begin["flow"], "response": a["auth"].response(begin["options"])}, a["headers"])["flow"]


def record(a):
    r = {"version": "REMANENCE-RECOVERY-V1", "generation": 1, "account_id": a["auth"].user, "bundle_id": a["bundle"],
        "credential_id": b64(a["auth"].id), "salt": b64(os.urandom(32)), "package_sealed": "", "prf_ark": "", "code_ark": "", "signature": ""}
    ctx = f"{r['version']}\n{r['account_id']}\n{r['bundle_id']}\n1\n{r['credential_id']}\n{r['salt']}"
    ark = os.urandom(32)
    e = proto_keyset_format.serialize(a["enc"], _secret_key_access.TOKEN)
    s = proto_keyset_format.serialize(a["sign"], _secret_key_access.TOKEN)
    plain = b"RPK1" + struct.pack(">I", len(e)) + e + struct.pack(">I", len(s)) + s
    def sealed(key, purpose, payload):
        iv = os.urandom(12); return b64(iv + AESGCM(key).encrypt(iv, payload, (ctx + purpose).encode()))
    def derive(secret, purpose):
        return HKDF(hashes.SHA256(), 32, unb64(r["salt"]), (ctx + purpose).encode()).derive(secret)
    r["package_sealed"] = sealed(ark, "\npackage", plain)
    r["prf_ark"] = sealed(derive(a["prf"], "\nprf-ark"), "\nprf-ark", ark)
    r["code_ark"] = sealed(derive(a["code"], "\ncode-ark"), "\ncode-ark", ark)
    r["signature"] = b64(bytes(69))
    r["signature"] = b64(a["sign"].primitive(signature.PublicKeySign).sign(manifest(EncryptedRecord(**r))))
    return r


def protect(client, a):
    register(client, a); flow = setup(client, a); r = record(a)
    post(client, "record", {"flow": flow, "record": r}, a["headers"])
    return r


def test_real_passkey_login_returns_same_account_and_opaque_identity(env):
    client, factory = env; a = seed(client); r = protect(client, a)
    begin = post(client, "login/options", {})
    out = post(client, "login/verify", {"flow": begin["flow"], "response": a["auth"].response(begin["options"])})
    assert out["authentication_verified"] and out["record"] == r
    assert out["login"]["user"]["user_id"] == a["auth"].user
    assert out["login"]["active_key_bundle"]["key_bundle_id"] == a["bundle"]
    assert client.get("/v1/me", headers={"Authorization": "Bearer " + out["login"]["access_token"]}).status_code == 200
    serialized = json.dumps(out)
    for secret in (b64(a["prf"]), b64(a["code"]), b64(proto_keyset_format.serialize(a["enc"], _secret_key_access.TOKEN))):
        assert secret not in serialized
    with factory() as db: assert db.get(RecoveryRecord, uuid.UUID(a["auth"].user)).payload == r


@pytest.mark.parametrize("change", ["origin", "uv", "rp", "signature", "handle", "challenge"])
def test_invalid_assertion_burns_challenge_and_never_logs_in(env, change):
    client, factory = env; a = seed(client); protect(client, a)
    begin = post(client, "login/options", {})
    kwargs = {"origin": "https://evil.example"} if change == "origin" else {"uv": False} if change == "uv" else {"rp": "evil.example"} if change == "rp" else {"handle": uuid.uuid4()} if change == "handle" else {}
    bad = a["auth"].response(begin["options"], **kwargs)
    if change == "signature": bad["response"]["signature"] = b64(bytes(70))
    if change == "challenge":
        modified = copy.deepcopy(begin["options"]); modified["challenge"] = b64(os.urandom(32)); bad = a["auth"].response(modified)
    with factory() as db: before = db.scalar(select(__import__("sqlalchemy").func.count()).select_from(AuthSession))
    post(client, "login/verify", {"flow": begin["flow"], "response": bad}, expected=400)
    post(client, "login/verify", {"flow": begin["flow"], "response": a["auth"].response(begin["options"])}, expected=400)
    with factory() as db: assert db.scalar(select(__import__("sqlalchemy").func.count()).select_from(AuthSession)) == before


def test_pending_registration_is_not_recoverable(env):
    client, _ = env; a = seed(client); register(client, a)
    assert client.get("/v1/recovery/record", headers=a["headers"]).status_code == 400
    begin = post(client, "login/options", {})
    post(client, "login/verify", {"flow": begin["flow"], "response": a["auth"].response(begin["options"])}, expected=400)


def test_owner_signature_required_and_record_immutable_with_exact_retry(env):
    client, _ = env; a = seed(client); r = protect(client, a)
    post(client, "record", {"flow": setup(client, a), "record": r}, a["headers"])
    different = record(a) # even a VALID owner signature cannot silently replace V1
    post(client, "record", {"flow": setup(client, a), "record": different}, a["headers"], 400)
    assert client.get("/v1/recovery/record", headers=a["headers"]).json()["record"] == r


def test_wrong_identity_signature_cannot_create_record(env):
    client, _ = env; a = seed(client); register(client, a); r = record(a)
    r["signature"] = b64(bytes(69))
    post(client, "record", {"flow": setup(client, a), "record": r}, a["headers"], 400)
    assert client.get("/v1/recovery/record", headers=a["headers"]).status_code == 400


def test_account_and_session_isolation(env):
    client, _ = env; a = seed(client); b = seed(client, "b"); protect(client, a)
    assert client.get("/v1/recovery/record", headers=b["headers"]).status_code == 400
    post(client, "setup/options", {"credential_id": b64(a["auth"].id)}, b["headers"], 400)
    begin = post(client, "setup/options", {"credential_id": b64(a["auth"].id)}, a["headers"])
    body = {"flow": begin["flow"], "response": a["auth"].response(begin["options"])}
    post(client, "setup/verify", body, b["headers"], 400)
    assert client.get("/v1/recovery/record").status_code == 401


def test_extensions_and_plaintext_unknown_fields_rejected_without_echo(env):
    client, _ = env; a = seed(client); register(client, a)
    begin = post(client, "setup/options", {"credential_id": b64(a["auth"].id)}, a["headers"])
    c = a["auth"].response(begin["options"]); secret = "DO_NOT_ECHO_PRF_SECRET"
    c["clientExtensionResults"] = {"prf": {"results": {"first": secret}}}
    result = client.post("/v1/recovery/setup/verify", json={"flow": begin["flow"], "response": c}, headers=a["headers"])
    assert result.status_code == 422 and secret not in result.text
    r = record(a); r["plaintext_ark"] = secret
    result = client.post("/v1/recovery/record", json={"flow": str(uuid.uuid4()), "record": r}, headers=a["headers"])
    assert result.status_code == 422 and secret not in result.text


def test_expired_and_successful_challenges_cannot_replay(env):
    client, factory = env; a = seed(client); protect(client, a)
    begin = post(client, "login/options", {}); body = {"flow": begin["flow"], "response": a["auth"].response(begin["options"])}
    post(client, "login/verify", body); post(client, "login/verify", body, expected=400)
    expired = post(client, "login/options", {})
    with factory() as db:
        db.get(RecoveryChallenge, uuid.UUID(expired["flow"])).expires_at = datetime.now(timezone.utc) - timedelta(seconds=1); db.commit()
    post(client, "login/verify", {"flow": expired["flow"], "response": a["auth"].response(expired["options"])}, expected=400)


def test_feature_disabled_by_default_and_forbidden_in_prod(env):
    client, _ = env; client.app.state.settings.recovery_enabled = False
    assert client.post("/v1/recovery/login/options", json={}).status_code == 404
    with pytest.raises(ValueError): Settings(mode=AppMode.PROD, recovery_enabled=True)


def test_concurrent_same_challenge_has_exactly_one_login_winner(env):
    client, factory = env; a = seed(client); protect(client, a)
    begin = post(client, "login/options", {}); body = {"flow": begin["flow"], "response": a["auth"].response(begin["options"])}
    with ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda _: client.post("/v1/recovery/login/verify", json=body).status_code, range(2)))
    assert sorted(statuses) == [200, 400]


def test_browser_route_and_assets_are_feature_gated_and_no_store(env):
    client, _ = env
    assert client.get("/recovery-test/").status_code == 404
    client.app.state.settings = client.app.state.settings.model_copy(update={"recovery_test_page_enabled": True})
    for path in ("/recovery-test/", "/recovery-test/crypto.mjs", "/recovery-test/app.mjs"):
        response = client.get(path)
        assert response.status_code == 200 and response.headers["cache-control"] == "no-store"
        assert "frame-ancestors 'none'" in response.headers["content-security-policy"]
    assert client.get("/recovery-test/secret.env").status_code == 404


@pytest.mark.parametrize("origin", ["http://remanence.hryshyn.dev", "https://evil.example", "https://remanence.hryshyn.dev/path", "android:apk-key-hash:invalid"])
def test_unsafe_origin_configuration_refused(origin):
    with pytest.raises(ValueError):
        Settings(mode=AppMode.TEST, recovery_enabled=True, recovery_origins=[origin])
