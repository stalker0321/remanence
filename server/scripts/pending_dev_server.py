"""Isolated dev-only pending server entrypoint (root runs this later).

Composes the real ``create_app`` with a synthetic pending custody
context (dev AEAD vault, ephemeral service signer, file-sink mock
email) and serves it on loopback. See
``remanence.pending.dev_bootstrap`` for the fail-closed rules.

Run from ``server/`` (root only)::

    uv run python scripts/pending_dev_server.py \\
        --database-url postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_<unique> \\
        --hmac-secret <32+char-secret> --pin-out /tmp/remanence-dev-pin.json \\
        --otp-sink /tmp/remanence-dev-otp.jsonl

Root prepares the unique DB + ``alembic upgrade head`` first (exact
commands in ``tasks/T185/dev-bootstrap.md``). Nothing here runs at
import time.
"""

from __future__ import annotations

from remanence.pending.dev_bootstrap import main

if __name__ == "__main__":
    raise SystemExit(main())
