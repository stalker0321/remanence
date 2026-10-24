"""Additional bounded real-DB concurrency, admission and committed-reply-loss cases."""
import copy
import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

from sqlalchemy import select

from test_me_endpoint import me_env, _seed
from test_google_auth import google_env, options
from test_atomic_registration import atomic, reserve, proof, candidate, final, grant, post, counts, headers, url
from test_passkey_recovery import Authenticator, b64
from remanence.registration_setup.models import RegistrationReservation as Reservation, RegistrationChallenge as Challenge
from remanence.registration_setup.service import AtomicRegistration
from remanence.settings import AppMode


def test_actual_commit_succeeds_but_commit_ack_is_lost(atomic, monkeypatch):
    a, body, _ = candidate(atomic)
    original_final = AtomicRegistration.finalize
    original_commit = atomic[1].class_.commit
    def finalized(self, row, payload):
        result = original_final(self, row, payload)
        if result[0] == 201: self.db.info["simulate_lost_commit_ack"] = True
        return result
    def lost_ack(self):
        original_commit(self)
        if self.info.pop("simulate_lost_commit_ack", False):
            raise ConnectionError("synthetic lost COMMIT acknowledgement")
    monkeypatch.setattr(AtomicRegistration, "finalize", finalized)
    monkeypatch.setattr(atomic[1].class_, "commit", lost_ack)
    result = final(atomic, a, body)
    assert result.status_code == 503 and result.json() == {"reason": "SETUP_UNAVAILABLE"}
    assert counts(atomic[1]) == [1] * 7
    status = atomic[0].get(url(a), headers=headers(a)).json()["reservation"]
    assert status["state"] == "ACTIVE" and status["outcome"]["operation_id"] == body["operation_id"]
    assert final(atomic, a, body).status_code == 200
    assert counts(atomic[1]) == [1] * 7
    assert proof(atomic).json()["login"]["user"]["user_id"] == a["reservation"]["account_id"]


def test_cancel_wins_race_before_old_finalize(atomic, monkeypatch):
    a, body, _ = candidate(atomic)
    reached, release, waiting = threading.Event(), threading.Event(), threading.Event()
    original_cancel = AtomicRegistration.cancel
    import remanence.registration_setup.service as module
    original_lock = module.lock_subject
    def pause(self, row):
        result = original_cancel(self, row)
        reached.set()
        assert release.wait(8)
        return result
    def observed(db, subject):
        if reached.is_set(): waiting.set()
        return original_lock(db, subject)
    monkeypatch.setattr(AtomicRegistration, "cancel", pause)
    monkeypatch.setattr(module, "lock_subject", observed)
    with ThreadPoolExecutor(max_workers=2) as pool:
        cancel = pool.submit(post, atomic, a, "cancel")
        try:
            assert reached.wait(8)
            old_final = pool.submit(final, atomic, a, body)
            assert waiting.wait(5)
            assert not old_final.done()
        finally: release.set()
        assert cancel.result(timeout=8)["reservation"]["state"] == "CANCELLED"
        assert old_final.result(timeout=8).status_code == 410
    assert counts(atomic[1]) == [0] * 7


def test_takeover_wins_race_before_old_finalize(atomic, monkeypatch):
    a, body, _ = candidate(atomic)
    reached, release, waiting = threading.Event(), threading.Event(), threading.Event()
    original = AtomicRegistration.reserve_verified
    import remanence.registration_setup.service as module
    original_lock = module.lock_subject
    def pause(self, subject, email):
        result = original(self, subject, email)
        reached.set()
        assert release.wait(8)
        return result
    def observed(db, subject):
        if reached.is_set(): waiting.set()
        return original_lock(db, subject)
    monkeypatch.setattr(AtomicRegistration, "reserve_verified", pause)
    monkeypatch.setattr(module, "lock_subject", observed)
    with ThreadPoolExecutor(max_workers=2) as pool:
        takeover = pool.submit(proof, atomic)
        try:
            assert reached.wait(8)
            old_final = pool.submit(final, atomic, a, body)
            assert waiting.wait(5)
            assert not old_final.done()
        finally: release.set()
        assert takeover.result(timeout=8).status_code == 200
        assert old_final.result(timeout=8).status_code == 401
    assert counts(atomic[1]) == [0] * 7


def test_changed_operation_race_and_active_cannot_cancel_restart_or_expire(atomic):
    a, body, _ = candidate(atomic)
    changed = copy.deepcopy(body); changed["operation_id"] = str(uuid.uuid4())
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = [pool.submit(final, atomic, a, payload) for payload in (body, changed)]
        results = [f.result(timeout=8) for f in replies]
    assert sorted(r.status_code for r in results) == [201, 409]
    assert counts(atomic[1]) == [1] * 7
    post(atomic, a, "cancel", expected=409)
    post(atomic, a, "restart", expected=409)
    with atomic[1].begin() as db:
        db.get(Reservation, uuid.UUID(a["reservation"]["id"])).expires_at = atomic[3][0] - timedelta(seconds=1)
    assert atomic[0].get(url(a), headers=headers(a)).json()["reservation"]["state"] == "ACTIVE"
    post(atomic, a, "leave")
    assert counts(atomic[1]) == [1] * 7


def test_expiry_precedes_final_and_deadlines_do_not_slide(atomic):
    a, body, _ = candidate(atomic)
    with atomic[1].begin() as db:
        db.get(Reservation, uuid.UUID(a["reservation"]["id"])).expires_at = atomic[3][0]
    assert final(atomic, a, body).status_code == 410
    terminal = reserve(atomic)
    assert terminal["reservation"]["state"] == "EXPIRED"
    assert terminal["reservation"]["account_id"] == a["reservation"]["account_id"]
    assert counts(atomic[1]) == [0] * 7


def test_credential_claim_race_has_one_winner(atomic):
    setups = [reserve(atomic, name) for name in ("first-subject", "second-subject")]
    auths = [Authenticator(a["reservation"]["account_id"]) for a in setups]
    auths[1].id, auths[1].key = auths[0].id, auths[0].key
    begins = [post(atomic, a, "credential/options") for a in setups]
    def verify(index):
        a, auth, begin = setups[index], auths[index], begins[index]
        return atomic[0].post(url(a, "credential/verify"), headers=headers(a), json={"epoch": a["reservation"]["epoch"],
            "flow": begin["flow"], "response": auth.response(begin["options"], True)})
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(verify, range(2)))
    assert sorted(r.status_code for r in replies) == [200, 409]
    assert counts(atomic[1]) == [0] * 7


def test_global_reservation_and_ceremony_budgets(atomic):
    a = reserve(atomic)
    now = atomic[3][0]
    with atomic[1].begin() as db:
        for i in range(511):
            db.add(Reservation(id=uuid.uuid4(), account_id=uuid.uuid4(), subject=f"budget-{i}", email=f"budget-{i}@example.com",
                state="RESERVED", epoch=1, is_current=True, setup_session_id=uuid.uuid4(), setup_expires_at=now,
                created_at=now, expires_at=now + timedelta(days=1), restart_authorized=False))
    assert proof(atomic, "over-budget").status_code == 429
    # Synthetic metadata fills only a budget, never substitutes for auth validation.
    with atomic[1].begin() as db:
        row = db.scalar(select(Reservation).where(Reservation.subject == "budget-0"))
        for _ in range(512):
            db.add(Challenge(id=uuid.uuid4(), reservation_id=row.id, epoch=1, setup_session_id=row.setup_session_id,
                purpose="REGISTER", challenge=os.urandom(32), expires_at=now + timedelta(seconds=300)))
    post(atomic, a, "credential/options", expected=429)
    assert counts(atomic[1]) == [0] * 7


def test_admission_off_still_allows_final_login_and_record(atomic):
    a, body, _ = candidate(atomic)
    atomic[0].app.state.settings.registration_atomic_admission_enabled = False
    created = final(atomic, a, body)
    assert created.status_code == 201
    assert proof(atomic).json()["status"] == "SIGNED_IN"
    assert proof(atomic, "new-subject").status_code == 403
    access = {"Authorization": "Bearer " + created.json()["login"]["access_token"]}
    assert atomic[0].get("/v1/recovery/record", headers=access).json()["record"] == body["record"]


def test_invalid_google_atomic_proof_rejected_by_real_verifier(atomic):
    assert proof(atomic, aud="wrong.apps.googleusercontent.com").status_code == 400
    assert proof(atomic, email_verified=False).status_code == 400
    assert counts(atomic[1]) == [0] * 7
    with atomic[1]() as db: assert list(db.scalars(select(Reservation))) == []


def test_inactive_routes_and_database_unavailable_are_private(me_env):
    client, _ = me_env
    path = "/v2/registration/reservations/" + str(uuid.uuid4())
    response = client.get(path)
    assert response.status_code == 404 and response.headers["cache-control"] == "no-store"
    cfg = client.app.state.settings
    cfg.recovery_enabled = cfg.google_sign_in_enabled = True
    cfg.google_client_ids = ["synthetic.apps.googleusercontent.com"]
    # Registration is available in every mode once Google and recovery are on.
    cfg.mode = AppMode.PROD
    client.app.state.session_factory = None
    response = client.get(path)
    assert response.status_code == 503 and response.json() == {"reason": "SETUP_UNAVAILABLE"}
    assert response.headers["cache-control"] == "no-store"


def test_directory_has_no_account_or_public_bundle_until_permanent_commit(atomic):
    observer = _seed(atomic[0], email="observer@example.com", handle="observer")
    observer_headers = {"Authorization": "Bearer " + observer["access_token"]}
    a, body, _ = candidate(atomic)
    paths = ["/v1/directory/handles/" + body["handle"], "/v1/directory/users/" + a["reservation"]["account_id"],
             "/v1/directory/key-bundles/" + body["key_bundle"]["key_bundle_id"]]
    assert [atomic[0].get(path, headers=observer_headers).status_code for path in paths] == [404] * 3
    assert final(atomic, a, body).status_code == 201
    assert [atomic[0].get(path, headers=observer_headers).status_code for path in paths] == [200] * 3


def test_malformed_and_private_public_credential_dto_never_echoes_secrets(atomic):
    a = reserve(atomic)
    begin = post(atomic, a, "credential/options")
    auth = Authenticator(a["reservation"]["account_id"])
    valid = auth.response(begin["options"], True)
    malformed = []
    no_client = copy.deepcopy(valid); del no_client["response"]["clientDataJSON"]; malformed.append(no_client)
    not_object = copy.deepcopy(valid); not_object["response"]["clientDataJSON"] = b64(b"[]"); malformed.append(not_object)
    secret = copy.deepcopy(valid); secret["clientExtensionResults"] = {"prf": "sentinel-private-secret"}; malformed.append(secret)
    for bad in malformed:
        response = atomic[0].post(url(a, "credential/verify"), headers=headers(a), json={"epoch": a["reservation"]["epoch"],
            "flow": begin["flow"], "response": bad})
        assert response.status_code == 422
        assert "sentinel-private-secret" not in response.text
        assert response.headers["cache-control"] == "no-store"
    post(atomic, a, "credential/verify", {"flow": begin["flow"], "response": valid})


def test_new_google_wire_requires_canonical_flow_and_nonce_without_burning_proof(atomic):
    begin = options(atomic[0])
    body = {"flow": begin["flow"], "nonce": begin["nonce"], "id_token": atomic[2](begin), "onboarding_version": 1}
    for field, value in (("flow", "{" + begin["flow"] + "}"), ("flow", begin["flow"].replace("-", "")),
                         ("nonce", begin["nonce"][:-1] + "B")):
        response = atomic[0].post("/v1/auth/google/verify", json={**body, field: value})
        assert response.status_code == 422 and response.headers["cache-control"] == "no-store"
    assert atomic[0].post("/v1/auth/google/verify", json=body).json()["status"] == "SETUP_REQUIRED"
