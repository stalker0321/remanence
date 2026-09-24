"""Bounded music staging loader CLI (manual invocation only).

Verifies a sample bundle (tracks.jsonl + coverage.json), then either
reports the dry-run plan (default: no database connection is opened, no
writes happen) or, with --execute, loads it into the music staging
tables of an explicit Postgres database.

Safety rules (all fail-closed, exit code 2):
- --database-url is required and must use postgresql+psycopg://; the URL
  is never logged (only host/database are echoed, password redacted).
- Bundle verification runs BEFORE any database touch: both files present,
  coverage schema_version == "v2", sha256(tracks.jsonl) matches coverage,
  line count matches coverage output_records.
- v1-shaped rows (no artist_mbids) are rejected by row validation, same
  as the library loader: missing_artist_mbids is counted, never merged.
- --execute additionally refuses when the music schema tables are absent
  (migration 0008 not applied). This tool never runs migrations, never
  creates schema, never touches the search index.

Run from ``server/`` with::

    uv run python scripts/music_load_staging.py \\
        --database-url postgresql+psycopg://user:secret@127.0.0.1:55432/remanence \\
        --sample-dir /path/to/sample-1000-v2

Add --execute to write. Without it, only verification + validation run.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.parse
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy import create_engine, inspect
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from remanence.music.staging.loader import (
    RowInvalid,
    load_staged_jsonl,
    validate_row,
)

SCHEMA_VERSION_REQUIRED = "v2"


class BundleError(RuntimeError):
    """Sample bundle failed verification (no database was touched)."""


@dataclass(slots=True)
class BundleReport:
    sample_dir: str
    track_lines: int
    sha256: str
    schema_version: str
    rows_valid: int = 0
    rows_invalid: int = 0
    invalid_reasons: dict[str, int] = field(default_factory=dict)


def _read_json(path: Path, label: str) -> object:
    try:
        return json.loads(path.read_bytes().decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BundleError(f"{label} unreadable: {path.name}") from exc


def verify_bundle(sample_dir: str | Path) -> BundleReport:
    """Verify bundle files and coverage claims. No database involved."""
    directory = Path(sample_dir)
    tracks_path = directory / "tracks.jsonl"
    coverage_path = directory / "coverage.json"
    if not tracks_path.is_file():
        raise BundleError(f"tracks.jsonl missing in {directory}")
    if not coverage_path.is_file():
        raise BundleError(f"coverage.json missing in {directory}")
    try:
        tracks_bytes = tracks_path.read_bytes()
    except OSError as exc:
        raise BundleError("tracks.jsonl unreadable") from exc
    coverage = _read_json(coverage_path, "coverage.json")
    if type(coverage) is not dict:
        raise BundleError("coverage.json must be an object")
    version = coverage.get("schema_version")
    if version != SCHEMA_VERSION_REQUIRED:
        raise BundleError(
            f"unsupported coverage schema_version: {version!r} "
            f"(required {SCHEMA_VERSION_REQUIRED!r})"
        )
    digest = hashlib.sha256(tracks_bytes).hexdigest()
    if coverage.get("output_sha256") != digest:
        raise BundleError("coverage output_sha256 does not match tracks.jsonl")
    try:
        decoded = tracks_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BundleError("tracks.jsonl is not valid UTF-8") from exc
    lines = decoded.splitlines()
    expected = coverage.get("output_records")
    if type(expected) is not int or expected != len(lines):
        raise BundleError(
            f"coverage output_records={expected!r} does not match "
            f"tracks.jsonl lines={len(lines)}"
        )
    return BundleReport(
        sample_dir=str(directory),
        track_lines=len(lines),
        sha256=digest,
        schema_version=str(version),
    )


def validate_bundle_rows(sample_dir: str | Path) -> BundleReport:
    """verify_bundle plus full per-row validation (still no database)."""
    report = verify_bundle(sample_dir)
    valid = 0
    invalid = 0
    reasons: dict[str, int] = {}
    tracks_path = Path(sample_dir) / "tracks.jsonl"
    with tracks_path.open("r", encoding="utf-8") as handle:
        for raw_line in handle:
            if not raw_line.strip():
                invalid += 1
                reasons["blank_line"] = reasons.get("blank_line", 0) + 1
                continue
            try:
                payload = json.loads(raw_line)
            except json.JSONDecodeError:
                invalid += 1
                reasons["bad_json"] = reasons.get("bad_json", 0) + 1
                continue
            try:
                validate_row(payload)
            except RowInvalid as exc:
                invalid += 1
                reasons[exc.reason] = reasons.get(exc.reason, 0) + 1
                continue
            valid += 1
    report.rows_valid = valid
    report.rows_invalid = invalid
    report.invalid_reasons = reasons
    return report


def _redacted_target(database_url: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(database_url)
        host = parsed.hostname or "?"
        port = f":{parsed.port}" if parsed.port else ""
        path = parsed.path or ""
        user = parsed.username or "?"
        return f"postgresql+psycopg://{user}@{host}{port}{path}"
    except ValueError:
        return "postgresql+psycopg://<unparseable>"


def _require_music_schema(engine: Engine) -> None:
    present = inspect(engine).has_table("music_track", schema="music")
    if not present:
        raise BundleError(
            "music.music_track absent: apply migration 0008_music_staging "
            "first; this tool never migrates"
        )


def run_load(
    database_url_or_engine: str | Engine,
    sample_dir: str | Path,
    *,
    batch_rows: int = 1000,
):
    """Verify the bundle, then load it. Engine may be injected (tests)."""
    report = validate_bundle_rows(sample_dir)
    if isinstance(database_url_or_engine, str):
        engine = create_engine(database_url_or_engine)
        close = True
    else:
        engine = database_url_or_engine
        close = False
    try:
        _require_music_schema(engine)
        with Session(engine) as session:
            stats = load_staged_jsonl(
                session, Path(sample_dir) / "tracks.jsonl", batch_rows=batch_rows
            )
    finally:
        if close:
            engine.dispose()
    return report, stats


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--sample-dir", required=True)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="actually write to the database (default is dry-run)",
    )
    parser.add_argument("--batch-rows", type=int, default=1000)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if not args.database_url.startswith("postgresql+psycopg://"):
        print(
            "refusing: --database-url must use postgresql+psycopg://",
            flush=True,
        )
        return 2
    if type(args.batch_rows) is not int or args.batch_rows <= 0:
        print("refusing: --batch-rows must be a positive int", flush=True)
        return 2
    try:
        report = validate_bundle_rows(args.sample_dir)
    except BundleError as exc:
        print(f"bundle refused: {exc}", flush=True)
        return 2
    target = _redacted_target(args.database_url)
    print(
        f"bundle ok: {report.track_lines} lines sha256={report.sha256[:16]}… "
        f"schema_version={report.schema_version} "
        f"valid={report.rows_valid} invalid={report.rows_invalid}",
        flush=True,
    )
    if not args.execute:
        print(
            f"dry-run: target {target}; no database connection opened, "
            "no writes performed. Re-run with --execute to write.",
            flush=True,
        )
        return 0
    print(f"executing load into {target}", flush=True)
    try:
        _, stats = run_load(args.database_url, args.sample_dir, batch_rows=args.batch_rows)
    except Exception as exc:
        print(f"load aborted: {type(exc).__name__}: {exc}", flush=True)
        return 2
    print(
        f"loaded: tracks_merged={stats.tracks_merged} "
        f"track_repeats={stats.track_repeats} artists_seen={stats.artists_seen} "
        f"external_ids_merged={stats.external_ids_merged} "
        f"isrc_collision_pairs={stats.isrc_collision_pairs} "
        f"batches_committed={stats.batches_committed}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
