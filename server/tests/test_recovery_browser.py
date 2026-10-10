"""Optional live HTTP/browser test; existing T160 Playwright installation reused."""
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

import pytest
import uvicorn
from test_me_endpoint import me_env
from test_passkey_recovery import seed, record, post
from remanence.api.recovery import b64, unb64

BROWSER_MODULE = os.environ.get("REMANENCE_PLAYWRIGHT_MODULE")


@pytest.mark.skipif(not BROWSER_MODULE, reason="existing Playwright module path required")
def test_browser_actual_http_passkey_code_and_missing_prf(me_env):
    client, _ = me_env
    sock=socket.socket(); sock.bind(("127.0.0.1",0)); port=sock.getsockname()[1]
    cfg=client.app.state.settings
    cfg.recovery_enabled=True; cfg.recovery_rp_id="localhost"; cfg.recovery_origins=[f"http://localhost:{port}"]
    server=uvicorn.Server(uvicorn.Config(client.app, log_level="critical", access_log=False))
    thread=threading.Thread(target=lambda:server.run(sockets=[sock]),daemon=True); thread.start()
    for _ in range(100):
        if server.started: break
        time.sleep(.02)
    assert server.started
    a=seed(client)
    script=Path(__file__).resolve().parents[2]/"tools/recovery/browser-e2e.mjs"
    process=subprocess.Popen(["node",str(script)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,
        env={**os.environ, "REMANENCE_PLAYWRIGHT_MODULE": BROWSER_MODULE})
    try:
        initial={"url":f"http://localhost:{port}","token":a["login"]["access_token"],"email":"a@example.com","password":"synthetic test password"}
        process.stdin.write(json.dumps(initial)+"\n"); process.stdin.flush()
        line=process.stdout.readline()
        if not line:
            error = process.stderr.read()
            reason = next((code for code in ("ERR_MODULE_NOT_FOUND", "SETUP_REJECTED", "SYNTHETIC_PRF_MISSING", "TypeError", "ReferenceError", "NotAllowedError", "Executable doesn't exist") if code in error), "UNCLASSIFIED")
            pytest.fail("browser enrollment failed: " + reason)
        enrolled=json.loads(line)
        a["auth"].id=unb64(enrolled["credential_id"]); a["prf"]=unb64(enrolled["prf"])
        post(client,"record",{"flow":enrolled["flow"],"record":record(a)},a["headers"])
        out, err=process.communicate(json.dumps({"code":b64(a["code"])})+"\n",timeout=45)
        assert process.returncode==0, "browser failed (subprocess output redacted)"
        result=json.loads(out.strip())
        assert result["status"]=="PASS" and result["missingPrfNoLoginSession"] and result["codeIdentityOpen"]
    finally:
        if process.poll() is None: process.terminate(); process.wait(timeout=10)
        server.should_exit=True; thread.join(timeout=10); sock.close()
