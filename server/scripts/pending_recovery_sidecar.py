"""Isolated recovery sidecar entrypoint (root runs this later).

Serves the EXISTING recovery routes against the SHARED epoch DB on a
fixed loopback bind, without restarting the pending epoch and without
any gateway/proxy. See ``remanence.recovery.sidecar_bootstrap``.

Run from ``server/`` (root only, pending epoch left running)::

    uv run python scripts/pending_recovery_sidecar.py \\
        --blob-root /tmp/remanence-recovery-blobs

with REMANENCE_DATABASE_URL set to the shared epoch DB
(``postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_<epoch>``).
Nothing here runs at import time.
"""

from __future__ import annotations

from remanence.recovery.sidecar_bootstrap import main

if __name__ == "__main__":
    raise SystemExit(main())
