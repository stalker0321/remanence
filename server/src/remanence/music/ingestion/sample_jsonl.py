"""Sample JSONL v2 output: deterministic serialization of TrackImport rows.

v2 adds ``artist_mbids`` (parallel to ``artists``, positionally aligned)
and nullable ``duration_ms`` to the v1 fields (track_id, title, artists,
recording_mbid, isrcs). Encoding mirrors the original sample-1000 run
byte-for-byte conventions (sort_keys, ensure_ascii=False, LF-joined with
trailing newline), so v2 is a strict field superset, not a reformat.

Strict fail-closed validation (ImporterError): every UUID re-parsed,
parallel arrays length-checked, duration restricted to None-or-positive
(never 0), ISRC shape re-checked. No name-derived identity exists on this
path: ``artist_mbids`` pass through from NormalizedRecord and are
validated as UUID strings only — the serializer never hashes, folds, or
merges display names.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import uuid
from pathlib import Path

from remanence.music.ingestion.identity import TrackImport, track_id_for_recording
from remanence.music.ingestion.mbdump import ImporterError

# File-level schema version (recorded in coverage/output dir naming, not
# per line — lines carry exactly the seven data fields below).
JSONL_SCHEMA_VERSION = "v2"

TRACK_FIELDS_V2 = frozenset(
    {
        "track_id",
        "title",
        "artists",
        "artist_mbids",
        "recording_mbid",
        "isrcs",
        "duration_ms",
    }
)

# Mirrors normalize._ISRC_RE (canonical shape definition lives there).
_ISRC_RE = re.compile(r"^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

# Total output budget (tracks + coverage), same as the original run.
MAX_OUTPUT_BYTES = 1024**3


def _valid_mbid(value: object, field: str) -> str:
    if type(value) is not str or not value:
        raise ImporterError(f"v2: bad {field}")
    try:
        return str(uuid.UUID(value)).lower()
    except (ValueError, AttributeError, TypeError):
        raise ImporterError(f"v2: bad {field}") from None


def _valid_isrc(value: object) -> str:
    if type(value) is not str or not value:
        raise ImporterError("v2: bad isrc")
    cleaned = value.strip().upper()
    if _ISRC_RE.fullmatch(cleaned) is None:
        raise ImporterError("v2: bad isrc")
    return cleaned


def serialize_track(item: object) -> dict[str, object]:
    """Validate one TrackImport and return its v2 JSON-able mapping."""
    if not isinstance(item, TrackImport):
        raise ImporterError("v2: expected TrackImport")
    record = item.record
    if type(record.title) is not str or not record.title.strip():
        raise ImporterError("v2: bad title")
    if (
        type(record.artists) not in (tuple, list)
        or not record.artists
        or any(type(name) is not str or not name.strip() for name in record.artists)
    ):
        raise ImporterError("v2: bad artists")
    if type(record.artist_mbids) not in (tuple, list):
        raise ImporterError("v2: bad artist_mbids")
    if len(record.artist_mbids) != len(record.artists):
        raise ImporterError("v2: artists/artist_mbids length mismatch")
    artist_mbids = [_valid_mbid(mbid, "artist_mbid") for mbid in record.artist_mbids]
    recording_mbid = _valid_mbid(record.recording_mbid, "recording_mbid")
    # Coherence: the assigned own ID must equal the deterministic mapping.
    if item.track_id != track_id_for_recording(recording_mbid):
        raise ImporterError("v2: track_id/recording_mbid mismatch")
    if type(record.isrcs) not in (tuple, list):
        raise ImporterError("v2: bad isrcs")
    isrcs = [_valid_isrc(code) for code in record.isrcs]
    duration_ms = record.duration_ms
    if duration_ms is not None and (
        type(duration_ms) is not int or duration_ms <= 0
    ):
        raise ImporterError("v2: bad duration_ms")
    return {
        "track_id": str(item.track_id),
        "title": record.title,
        "artists": list(record.artists),
        "artist_mbids": artist_mbids,
        "recording_mbid": recording_mbid,
        "isrcs": isrcs,
        "duration_ms": duration_ms,
    }


def serialize_sample_jsonl(
    imports: tuple[TrackImport, ...] | list[TrackImport],
) -> bytes:
    """Deterministically serialize imports (sorted by track_id) to JSONL."""
    ordered = sorted(imports, key=lambda item: str(item.track_id))
    lines = [
        json.dumps(serialize_track(item), ensure_ascii=False, sort_keys=True)
        for item in ordered
    ]
    return ("\n".join(lines) + "\n" if lines else "").encode("utf-8")


def serialize_coverage(coverage: dict[str, object]) -> bytes:
    """Deterministically serialize a coverage mapping."""
    if type(coverage) is not dict:
        raise ImporterError("v2: coverage must be a mapping")
    return (
        json.dumps(coverage, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    ).encode("utf-8")


def write_sample_output(
    final_dir: str | Path,
    tracks_payload: bytes,
    coverage_payload: bytes,
    *,
    max_bytes: int = MAX_OUTPUT_BYTES,
) -> tuple[Path, Path]:
    """Atomically publish the sample directory (stage + single rename).

    Both files become visible together or not at all: payloads are
    written into a sibling staging directory and fsynced, then the
    staging directory is renamed onto the final path in ONE atomic
    operation. If the final path already exists the run refuses — no
    overwrites, no partial visible result. A leftover staging directory
    (crashed run) also refuses; remove it only after review.
    """
    if type(tracks_payload) is not bytes or type(coverage_payload) is not bytes:
        raise ImporterError("v2: payloads must be bytes")
    if type(max_bytes) is not int or max_bytes <= 0:
        raise ImporterError("v2: max_bytes must be a positive int")
    if len(tracks_payload) + len(coverage_payload) > max_bytes:
        raise ImporterError("v2: output over byte budget")
    final = Path(final_dir)
    if not final.name or final.name in (".", ".."):
        raise ImporterError("v2: invalid output directory name")
    if os.path.lexists(final):
        raise ImporterError(f"v2: refusing to overwrite existing: {final.name}")
    staging = final.parent / (final.name + ".staging")
    if os.path.lexists(staging):
        raise ImporterError(
            f"v2: stale staging directory present (clean after review): {staging.name}"
        )
    try:
        staging.mkdir(parents=True)
        tracks_tmp = staging / "tracks.jsonl"
        coverage_tmp = staging / "coverage.json"
        for path, payload in ((tracks_tmp, tracks_payload), (coverage_tmp, coverage_payload)):
            with open(path, "wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
        os.rename(staging, final)
    except OSError as exc:
        shutil.rmtree(staging, ignore_errors=True)
        raise ImporterError(f"v2: output publish failed: {exc}") from exc
    return final / "tracks.jsonl", final / "coverage.json"
