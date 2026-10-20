"""Opt-in synthetic-account browser -> loopback tunnel -> real FastAPI/PG.

No test endpoints or auth overrides. Existing fixture owns the unique database.
The outer runner owns server flock; the remote command takes web flock once,
nonblocking, while this API is active. Only this test's Popen/socket are stopped.
"""

import json
import os
from pathlib import Path
import re
import selectors
import socket
import subprocess
from datetime import datetime, timezone
from threading import Event, Thread
from time import monotonic

import pytest
from sqlalchemy import select, text
from tink import tink_config
import uvicorn

from composed_support import publication, world
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode as Mode
from remanence.send_access.service import grant_lifetime

pytest_plugins = ("test_session_repository_create",)
SSH = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
       "-i", "/home/vodkolyan/.ssh/vuzol_workers"]
REMOTE = "hryshyn_dev@34.147.53.61"


def read_line(stream, timeout=35):
    with selectors.DefaultSelector() as selector:
        selector.register(stream, selectors.EVENT_READ)
        if not selector.select(timeout):
            raise AssertionError("bounded test controller response timed out")
        return stream.readline()


def remote(command):
    return subprocess.run(SSH + [REMOTE, command], capture_output=True, text=True, timeout=15, check=True).stdout


def stop_owned(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


@pytest.mark.skipif(os.environ.get("T384_LOOPBACK_BROWSER") != "1", reason="explicit isolated loopback browser runner only")
def test_actual_app_against_composed_loopback_api(session_factory, tmp_path, monkeypatch):
    tink_config.register()
    snapshot = os.environ["T384_REMOTE_SNAPSHOT"]
    remote_proof = os.environ["T384_REMOTE_PROOF"]
    proof = Path(os.environ["T384_LOCAL_PROOF"])
    assert re.fullmatch(r"/srv/remanence/src/t384-[a-z0-9-]+", snapshot)
    assert re.fullmatch(r"/srv/remanence/evidence/t384-[a-z0-9-]+/real-[a-z0-9-]+", remote_proof)
    assert str(proof).startswith("/home/vodkolyan/remanence-agent-control/tasks/T384/")
    assert proof.is_dir()
    app, sender, recipient, headers = world(session_factory, monkeypatch)
    assert not app.dependency_overrides
    with session_factory() as observer:
        database = observer.scalar(text("SELECT current_database()"))
        assert re.fullmatch(r"remanence_tmp_[0-9a-f]{32}", database)
        assert observer.scalar(text("SELECT version_num FROM alembic_version")) == "0017_send_access"
    audit, effects = [], []

    class Audit:
        async def __call__(self, scope, receive, send):
            async def observed(message):
                if scope["type"] == "http" and message["type"] == "http.response.start":
                    audit.append({"method": scope["method"], "path": scope["path"], "status": message["status"],
                                  "bearer_present": any(key == b"authorization" for key, _ in scope["headers"])})
                await send(message)
            await app(scope, receive, observed)

    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(128)
    api_port = listener.getsockname()[1]
    assert api_port not in (80, 443, 18047)
    server = uvicorn.Server(uvicorn.Config(Audit(), access_log=False, log_level="warning", log_config=None))
    thread = Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    tunnel = browser = None
    tunnel_port = browser_port = browser_pid = None
    outcome = "FAILED"
    thread.start()

    def effect(phase):
        with session_factory() as session:
            rows = list(session.scalars(select(SendPublication).where(SendPublication.account_id == sender.user.id)))
            access = session.get(AccountSendAccess, sender.user.id)
            value = {"phase": phase, "lifetime": access.lifetime_granted_at is not None,
                     "receipts": sorted((row.bucket, row.funding, row.free_slot) for row in rows)}
            effects.append(value)
            return value

    def exchange(payload):
        browser.stdin.write(json.dumps(payload) + "\n")
        browser.stdin.flush()
        line = read_line(browser.stdout)
        if not line:
            raise AssertionError(f"remote browser exited before {payload['phase']}; see bounded browser stderr")
        response = json.loads(line)
        assert response["phase"] == payload["phase"] and response["ok"] is True
        return response

    try:
        until = monotonic() + 5
        while not server.started:
            assert monotonic() < until and thread.is_alive(), "own loopback API startup failed"
            Event().wait(0.02)
        tunnel = subprocess.Popen(SSH + ["-N", "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=10",
                                       "-R", f"127.0.0.1:0:127.0.0.1:{api_port}", REMOTE],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        until = monotonic() + 12
        while tunnel_port is None:
            line = read_line(tunnel.stderr, max(0.1, until - monotonic()))
            match = re.search(r"Allocated port (\d+) for remote forward", line)
            if match:
                tunnel_port = int(match.group(1))
            else:
                assert line and monotonic() < until, "own SSH loopback forwarding unavailable"
        listeners = remote(f"ss -ltnH 'sport = :{tunnel_port}'").splitlines()
        assert listeners and all(row.split()[3] in (f"127.0.0.1:{tunnel_port}", f"[::1]:{tunnel_port}") for row in listeners), "tunnel must be loopback ONLY"
        # Remote paths are validated above. No token in command, environment or files.
        command = (f"source /srv/remanence/tools/env.sh && test ! -e '{remote_proof}' && mkdir '{remote_proof}' && "
                   f"flock -n -E 75 /tmp/remanence-web.lock timeout 150 node "
                   f"'{snapshot}/web/test/browser/realBackendCheck.mjs' '{snapshot}/web' '{remote_proof}'")
        with (proof / "browser-stderr.raw.log").open("w") as errors:
            browser = subprocess.Popen(SSH + [REMOTE, command], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=errors, text=True, bufsize=1)
            first = exchange({"phase": "fresh", "apiPort": tunnel_port, "session": {
                "accountId": str(sender.user.id), "bundleId": str(sender.bundle.id), "handle": "t384_sender",
                "accessToken": headers["Authorization"].removeprefix("Bearer "),
                "refreshToken": "unused-synthetic-refresh", "accessExpiresAt": "2031-01-01T00:00:00Z",
                "refreshExpiresAt": "2031-02-01T00:00:00Z",
            }})
            browser_port, browser_pid = first["browserPort"], first["pid"]
            assert effect("fresh")["receipts"] == []
            own = publication(session_factory, tmp_path, sender, sender)
            exchange({"phase": "self", "self": 1, "other": 2})
            assert effect("self")["receipts"] == [("SELF", "FREE", 1)]
            other = publication(session_factory, tmp_path, sender, recipient)
            exchange({"phase": "other", "self": 1, "other": 1})
            pending = publication(session_factory, tmp_path, sender, None, "v2")
            exchange({"phase": "pending", "self": 1, "other": 0, "otherId": str(other.id),
                      "pendingCapsuleId": str(pending.capsule_id), "pendingId": str(pending.id)})
            before = effect("pending")["receipts"]
            assert before == [("OTHER", "FREE", 1), ("OTHER", "FREE", 2), ("SELF", "FREE", 1)]
            exchange({"phase": "cancel"})
            assert effect("cancel")["receipts"] == before
            with session_factory() as observer:
                assert own.row(observer).state == "READY"
                assert other.row(observer).state == "REVOKED"
                assert pending.row(observer).state == "CANCELLED"
            app.state.settings = app.state.settings.model_copy(update={"send_access_mode": Mode.RECORD})
            exchange({"phase": "record", "self": 1, "other": 0, "display": "record", "mode": "record"})
            assert effect("record")["receipts"] == before
            with session_factory() as writer:
                grant_lifetime(writer, account_id=sender.user.id, grant_key="t384-synthetic-only", origin="test", now=datetime.now(timezone.utc))
                writer.commit()
            app.state.settings = app.state.settings.model_copy(update={"send_access_mode": Mode.ENFORCE})
            exchange({"phase": "lifetime", "self": 1, "other": 0, "display": "lifetime", "lifetime": True})
            assert effect("lifetime")["receipts"] == before and effects[-1]["lifetime"]
            app.state.settings = app.state.settings.model_copy(update={"send_access_mode": Mode.OFF})
            exchange({"phase": "off", "display": "off"})
            browser.stdin.close()
            assert browser.wait(timeout=15) == 0, "real browser assertions failed; see own proof"
            assert not app.dependency_overrides
            outcome = "PASS"
    finally:
        stop_owned(browser)
        stop_owned(tunnel)
        server.should_exit = True
        thread.join(timeout=5)
        listener.close()
        runtime = {"outcome": outcome, "unique_database": database, "api_host": "127.0.0.1", "api_port": api_port,
                   "tunnel_host": "127.0.0.1", "tunnel_port": tunnel_port, "browser_port": browser_port,
                   "browser_pid": browser_pid, "tunnel_pid": tunnel.pid if tunnel else None,
                   "server_stopped": not thread.is_alive(), "tunnel_stopped": tunnel is None or tunnel.poll() is not None,
                   "browser_ssh_stopped": browser is None or browser.poll() is not None,
                   "requests": audit, "db_effects": effects}
        (proof / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n")
        assert not thread.is_alive(), "own API did not stop before unique fixture DB teardown"
        for port in (tunnel_port, browser_port):
            if port is not None:
                assert not remote(f"ss -ltnH 'sport = :{port}'").strip(), "own ephemeral remote listener remains"
