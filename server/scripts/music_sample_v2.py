"""Bounded real sample-v2 run: archive -> sample JSONL v2 directory.

Run from ``server/`` with::

    uv run python scripts/music_sample_v2.py \
        --tar /path/to/mbdump.tar.bz2 --outdir /path/to/sample-1000-v2

Calls the audited ``run_sample`` (streaming, fail-closed caps), serializes
the v2 payload (artists + parallel artist_mbids + nullable duration_ms),
and atomically publishes the output directory (single rename, refuses to
overwrite). No database, no search index, no endpoints, no deploy.

Exit codes: 0 success; 2 importer/output abort (nothing finalized).
"""

from __future__ import annotations

import argparse
import hashlib
import resource
import sys
import time
import traceback
from pathlib import Path

ARTIST_LIMIT_DEFAULT = 1000
# Audited guarded value for the measured 4,577,805,938-byte recording member.
MEMBER_BYTE_CAP_DEFAULT = 5_500_000_000


def log(message: str) -> None:
    print(message, flush=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tar", required=True, type=Path)
    parser.add_argument("--outdir", required=True, type=Path)
    parser.add_argument("--artist-limit", type=int, default=ARTIST_LIMIT_DEFAULT)
    parser.add_argument("--member-byte-cap", type=int, default=MEMBER_BYTE_CAP_DEFAULT)
    args = parser.parse_args(argv)
    if args.artist_limit <= 0 or args.member_byte_cap <= 0:
        parser.error("limits must be positive ints")
    return args


def main(argv: list[str] | None = None) -> int:
    from remanence.music.ingestion.sample import run_sample
    from remanence.music.ingestion.sample_jsonl import (
        JSONL_SCHEMA_VERSION,
        serialize_coverage,
        serialize_sample_jsonl,
        write_sample_output,
    )

    args = parse_args(argv)
    if not args.tar.is_file():
        log(f"ABORT phase=preflight: archive missing: {args.tar}")
        return 2
    start = time.monotonic()
    try:
        result = run_sample(
            args.tar,
            artist_limit=args.artist_limit,
            member_byte_cap=args.member_byte_cap,
        )
    except Exception as exc:
        log(
            f"ABORT phase=run_sample elapsed_s={time.monotonic() - start:.1f} "
            f"error={type(exc).__name__}: {exc}"
        )
        traceback.print_exc()
        return 2

    elapsed = time.monotonic() - start
    try:
        tracks_payload = serialize_sample_jsonl(result.imports)
        max_rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        coverage = result.coverage.to_dict()
        coverage["schema_version"] = JSONL_SCHEMA_VERSION
        coverage["elapsed_s"] = round(elapsed, 1)
        coverage["max_rss_mb"] = round(max_rss_kb / 1024, 1)
        coverage["output_sha256"] = hashlib.sha256(tracks_payload).hexdigest()
        coverage["output_records"] = len(result.imports)
        coverage_payload = serialize_coverage(coverage)
        tracks_path, coverage_path = write_sample_output(
            args.outdir, tracks_payload, coverage_payload
        )
    except Exception as exc:
        log(
            f"ABORT phase=publish elapsed_s={time.monotonic() - start:.1f} "
            f"error={type(exc).__name__}: {exc}"
        )
        traceback.print_exc()
        return 2

    log(
        f"SUCCESS records={len(result.imports)} elapsed_s={elapsed:.1f} "
        f"max_rss_mb={max_rss_kb / 1024:.1f} sha256={coverage['output_sha256']} "
        f"outdir={tracks_path.parent}"
    )
    ordered = sorted(result.imports, key=lambda item: str(item.track_id))
    for pos, item in enumerate(ordered[:10], start=1):
        record = item.record
        log(
            f"SANITY {pos}/10 title={record.title!r} "
            f"artists={list(record.artists)!r} "
            f"artist_mbids={list(record.artist_mbids)!r} "
            f"mbid={record.recording_mbid} duration_ms={record.duration_ms!r} "
            f"isrcs={list(record.isrcs)!r}"
        )
    _ = coverage_path
    return 0


if __name__ == "__main__":
    sys.exit(main())
