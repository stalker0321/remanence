"""Opt-in T398 actual browser publication/receive -> unchanged FastAPI -> unique PG.

Fixture seeds only synthetic users, public golden key bundles and real bearer
sessions. No auth/endpoint/clock overrides. Browser performs actual signed
publication, uploads ciphertext, recognition, decrypt, first-open and revoke.
"""
import json
import os
from pathlib import Path
import re
import socket
import subprocess
from threading import Event, Thread
from time import monotonic
from uuid import UUID
from datetime import datetime, timezone

from google.protobuf.json_format import ParseDict
import pytest
from sqlalchemy import select, text
from tink.proto import tink_pb2
import uvicorn

from remanence.main import create_app
from remanence.capsules.models import Capsule
from remanence.users.models import User
from remanence.users.key_models import UserKeyBundle, KeyBundleStatus
from remanence.send_access.models import SendPublication
from remanence.send_access.service import provision
from remanence.send_access.policy import SendAccessMode
from remanence.settings import Settings, AppMode
from remanence.storage import LocalFileBlobStore, CiphertextStager
from send_access_http_support import bearer
from test_cross_stack_browser import SSH, REMOTE, read_line, remote, stop_owned

pytest_plugins = ("test_session_repository_create",)
SENDER = UUID("11111111-2222-4333-8444-555555555555")
SENDER_BUNDLE = UUID("22222222-3333-4444-8555-666666666666")
RECIPIENT = UUID("33333333-4444-4555-8666-777777777777")
RECIPIENT_BUNDLE = UUID("44444444-5555-4666-8777-888888888888")


def fixture_world(factory, tmp_path):
    vectors = Path(__file__).resolve().parents[2] / "protocol" / "fixtures"
    def public(name):
        data = json.loads((vectors / name).read_text())["fixed_public_keyset_json"]
        return ParseDict(data, tink_pb2.Keyset()).SerializeToString()
    hpke, signing = public("recipient-envelope-v1.json"), public("publish-signature-v1.json")
    credentials = {}
    with factory() as session:
        for role, account, bundle, handle in [("sender", SENDER, SENDER_BUNDLE, "sergio"), ("recipient", RECIPIENT, RECIPIENT_BUNDLE, "ada")]:
            session.add(User(id=account, email_normalized=f"t398-{role}@example.test", handle_normalized=handle, handle_display=handle))
            session.flush()
            session.add(UserKeyBundle(id=bundle, user_id=account, encryption_public_keyset=hpke,
                                      signing_public_keyset=signing, suite="HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
                                      protocol_version=1, status=KeyBundleStatus.ACTIVE))
            provision(session, account, now=datetime.now(timezone.utc))
            headers, _ = bearer(session, account)
            credentials[role] = dict(accountId=str(account), bundleId=str(bundle), handle=handle,
                                     accessToken=headers["Authorization"].removeprefix("Bearer "),
                                     refreshToken="unused-synthetic-refresh", accessExpiresAt="2031-01-01T00:00:00Z",
                                     refreshExpiresAt="2031-02-01T00:00:00Z")
        session.commit()
    app = create_app(Settings(mode=AppMode.TEST, send_access_mode=SendAccessMode.ENFORCE),
                     session_factory=factory, blob_store=LocalFileBlobStore(tmp_path / "blobs"),
                     ciphertext_stager=CiphertextStager(tmp_path / "staging"))
    assert not app.dependency_overrides
    return app, credentials


@pytest.mark.skipif(os.environ.get("T398_LOOPBACK_BROWSER") != "1", reason="explicit isolated loopback runner only")
def test_real_crypto_browser_admission_revoke(session_factory, tmp_path):
    snapshot, remote_proof = os.environ["T398_REMOTE_SNAPSHOT"], os.environ["T398_REMOTE_PROOF"]
    proof = Path(os.environ["T398_LOCAL_PROOF"])
    assert re.fullmatch(r"/srv/remanence/src/t398-[a-z0-9-]+", snapshot)
    assert re.fullmatch(r"/srv/remanence/evidence/t398-[a-z0-9-]+/real-[a-z0-9-]+", remote_proof)
    assert str(proof).startswith("/home/vodkolyan/remanence-agent-control/tasks/T398/") and proof.is_dir()
    app, credentials = fixture_world(session_factory, tmp_path)
    with session_factory() as observer:
        database = observer.scalar(text("SELECT current_database()"))
        assert re.fullmatch(r"remanence_tmp_[0-9a-f]{32}", database)
        assert observer.scalar(text("SELECT version_num FROM alembic_version")) == "0017_send_access"
    audit, effects = [], []

    class Audit:
        async def __call__(self, scope, receive, send):
            async def observed(message):
                if scope["type"] == "http" and message["type"] == "http.response.start":
                    audit.append(dict(method=scope["method"], path=scope["path"], status=message["status"],
                                      bearer_present=any(key == b"authorization" for key, _ in scope["headers"])))
                await send(message)
            await app(scope, receive, observed)

    listener = socket.socket(); listener.bind(("127.0.0.1", 0)); listener.listen(128)
    api_port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(Audit(), access_log=False, log_level="warning", log_config=None))
    thread = Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    tunnel = browser = None
    tunnel_port = browser_port = None
    outcome = "FAILED"
    thread.start()

    def exchange(phase, **data):
        browser.stdin.write(json.dumps(dict(phase=phase, **data)) + "\n"); browser.stdin.flush()
        line = read_line(browser.stdout, timeout=140)
        assert line, f"browser exited during {phase}; see browser-stderr.raw.log"
        response = json.loads(line)
        assert response["phase"] == phase and response["ok"] is True
        return response

    def effect(phase, capsule_id, opened):
        with session_factory() as observer:
            row = observer.get(Capsule, UUID(capsule_id))
            assert row is not None and row.sender_user_id == SENDER and row.recipient_user_id == RECIPIENT
            assert row.state == ("READY" if opened else "REVOKED")
            assert (row.first_opened_at is not None) == opened
            receipts = list(observer.scalars(select(SendPublication)))
            assert len(receipts) == (2 if opened else 1)
            value = dict(phase=phase, capsule_id=capsule_id, state=row.state,
                         first_opened_at=row.first_opened_at.isoformat() if opened else None,
                         receipts=len(receipts))
            effects.append(value)
            return value
    try:
        until = monotonic() + 5
        while not server.started:
            assert monotonic() < until and thread.is_alive()
            Event().wait(0.02)
        tunnel = subprocess.Popen(SSH + ["-N", "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=10",
                                       "-R", f"127.0.0.1:0:127.0.0.1:{api_port}", REMOTE],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        until = monotonic() + 12
        while tunnel_port is None:
            line = read_line(tunnel.stderr, max(0.1, until - monotonic()))
            match = re.search(r"Allocated port (\d+) for remote forward", line)
            if match: tunnel_port = int(match.group(1))
            else: assert line and monotonic() < until
        listeners = remote(f"ss -ltnH 'sport = :{tunnel_port}'").splitlines()
        assert listeners and all(row.split()[3] in (f"127.0.0.1:{tunnel_port}", f"[::1]:{tunnel_port}") for row in listeners)
        command = (f"source /srv/remanence/tools/env.sh && test ! -e '{remote_proof}' && "
                   f"mkdir '{remote_proof}' '{remote_proof}/tmp' && export TMPDIR='{remote_proof}/tmp' && "
                   f"flock -n -E 75 /tmp/remanence-web.lock timeout 600 node "
                   f"'{snapshot}/web/test/browser/realAdmissionCheck.mjs' '{snapshot}/web' '{remote_proof}'")
        with (proof / "browser-stderr.raw.log").open("w") as errors:
            browser = subprocess.Popen(SSH + [REMOTE, command], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=errors, text=True, bufsize=1)
            first = exchange("start", apiPort=tunnel_port, credentials=credentials)
            browser_port = first["browserPort"]
            denied = exchange("revoked"); effect("revoked", denied["capsuleId"], False)
            late = exchange("back"); committed = effect("back", late["capsuleId"], True)
            for phase in ("replay", "unknown"):
                response = exchange(phase)
                assert effect(phase, response["capsuleId"], True)["first_opened_at"] == committed["first_opened_at"]
            exchange("finish")
            browser.stdin.close()
            assert browser.wait(timeout=15) == 0
            assert not app.dependency_overrides
            outcome = "PASS"
    finally:
        stop_owned(browser); stop_owned(tunnel)
        server.should_exit = True; thread.join(timeout=5); listener.close()
        runtime = dict(outcome=outcome, unique_database=database, api_host="127.0.0.1", api_port=api_port,
                       tunnel_port=tunnel_port, browser_port=browser_port, server_stopped=not thread.is_alive(),
                       tunnel_stopped=tunnel is None or tunnel.poll() is not None,
                       browser_ssh_stopped=browser is None or browser.poll() is not None,
                       requests=audit, db_effects=effects)
        (proof / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n")
        assert not thread.is_alive()
        for port in (tunnel_port, browser_port):
            if port is not None: assert not remote(f"ss -ltnH 'sport = :{port}'").strip(), "owned listener remains"
