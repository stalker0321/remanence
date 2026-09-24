"""Sample JSONL v2 tests: artist_mbids parallel to artists + duration.

No name-derived identity anywhere on this path: same display names with
different MBIDs stay distinct, and the module never hashes display names
(AST-guarded). Uses hand-built records plus the synthetic fixture tar —
no real archive, no DB, no index.
"""

from __future__ import annotations

import ast
import io
import json
import tarfile
import uuid
from pathlib import Path

import pytest

from remanence.music.ingestion.identity import TrackImport, track_id_for_recording
from remanence.music.ingestion.limits import SAMPLE_TABLES
from remanence.music.ingestion.mbdump import ImporterError
from remanence.music.ingestion.normalize import NormalizedRecord
from remanence.music.ingestion.sample import run_sample
from remanence.music.ingestion.sample_jsonl import (
    JSONL_SCHEMA_VERSION,
    TRACK_FIELDS_V2,
    serialize_coverage,
    serialize_sample_jsonl,
    serialize_track,
    write_sample_output,
)

_FIXTURES = Path(__file__).resolve().parent / "fixtures" / "music" / "mbdump_sample"

_KATE_A = "9515c74c-6e9f-5554-b1fa-f2be4fda183c"
_KATE_B = "78a690af-9021-539e-82ac-eba016a62ccb"
_REC1 = "e4e4acd3-576f-5dd5-aefc-d051be3566ea"
_REC2 = "81096406-f791-5265-9374-dac23cff638a"
_REC3 = "96ff605b-1287-537f-bd6c-f0d202d86955"
_DUO_A = "ae0ba970-4294-5fdd-8f0b-37462e893bde"
_DUO_B = "f1fbd43b-3e72-56fe-96b9-a068355a9a0f"


def _record(
    title: str,
    artists: tuple[str, ...],
    artist_mbids: tuple[str, ...],
    recording_mbid: str,
    duration_ms: int | None = None,
    isrcs: tuple[str, ...] = (),
) -> NormalizedRecord:
    return NormalizedRecord(
        title=title,
        normalized_title=title.strip().lower(),
        artists=artists,
        normalized_artists=tuple(a.strip().lower() for a in artists),
        duration_ms=duration_ms,
        recording_mbid=recording_mbid,
        artist_mbids=artist_mbids,
        isrcs=isrcs,
    )


def _import(record: NormalizedRecord) -> TrackImport:
    return TrackImport(
        track_id=track_id_for_recording(record.recording_mbid),
        record=record,
        external_ids=(),
    )


def test_v2_schema_keys_exact() -> None:
    item = _import(_record("Spirit", ("Willy",), (_KATE_A,), _REC1, 253000, ("JPM200400150",)))
    row = serialize_track(item)
    assert set(row) == set(TRACK_FIELDS_V2) == {
        "track_id", "title", "artists", "artist_mbids",
        "recording_mbid", "isrcs", "duration_ms",
    }
    assert JSONL_SCHEMA_VERSION == "v2"


def test_artist_mbids_parallel_and_validated() -> None:
    item = _import(
        _record("Duo", ("Alpha", "Beta"), (_DUO_A, _DUO_B), _REC2, None, ())
    )
    row = serialize_track(item)
    assert row["artists"] == ["Alpha", "Beta"]
    assert row["artist_mbids"] == [_DUO_A, _DUO_B]
    assert row["duration_ms"] is None
    for mbid in row["artist_mbids"]:
        uuid.UUID(mbid)


def test_same_display_name_different_mbids_stay_distinct() -> None:
    """Homonym safety: identical names never merge on the v2 path."""
    first = _import(_record("Song One", ("Kate",), (_KATE_A,), _REC1, 200000, ()))
    second = _import(_record("Song Two", ("Kate",), (_KATE_B,), _REC2, 210000, ()))
    payload = serialize_sample_jsonl([first, second])
    rows = [json.loads(line) for line in payload.decode("utf-8").splitlines()]
    assert len(rows) == 2
    assert rows[0]["artists"] == rows[1]["artists"] == ["Kate"]
    assert {rows[0]["artist_mbids"][0], rows[1]["artist_mbids"][0]} == {_KATE_A, _KATE_B}
    assert rows[0]["track_id"] != rows[1]["track_id"]


def test_mixed_credits_preserve_order() -> None:
    item = _import(
        _record("Trio", ("Zed", "Amy", "Moe"), (_REC1, _REC2, _REC3), _REC3, 180000, ())
    )
    row = serialize_track(item)
    assert row["artists"] == ["Zed", "Amy", "Moe"]
    assert row["artist_mbids"] == [_REC1, _REC2, _REC3]


def test_no_name_derived_identity_in_module() -> None:
    """AST guard: the v2 path must never hash display names into identity."""
    module = (
        Path(__file__).resolve().parent.parent
        / "src" / "remanence" / "music" / "ingestion" / "sample_jsonl.py"
    )
    tree = ast.parse(module.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            func = node.func
            dotted = ""
            if isinstance(func, ast.Attribute):
                dotted = func.attr
            elif isinstance(func, ast.Name):
                dotted = func.id
            assert dotted not in {"uuid5", "uuid4", "artist_id_for_name"}, dotted
    source = module.read_text(encoding="utf-8")
    assert "music:artist:" not in source
    assert "normalize_text" not in source


def test_validation_failures_fail_closed() -> None:
    good = _record("T", ("A",), (_KATE_A,), _REC1, 1000, ())
    with pytest.raises(ImporterError):
        serialize_track(_import(_record("T", ("A", "B"), (_KATE_A,), _REC1, 1000, ())))
    bad_mbid = _record("T", ("A",), ("not-a-uuid",), _REC1, 1000, ())
    with pytest.raises(ImporterError):
        serialize_track(_import(bad_mbid))
    bad_rec = _record("T", ("A",), (_KATE_A,), "zzz", 1000, ())
    bad_item = TrackImport(
        track_id=uuid.uuid4(), record=bad_rec, external_ids=()
    )
    with pytest.raises(ImporterError):
        serialize_track(bad_item)
    bad_dur = _record("T", ("A",), (_KATE_A,), _REC1, 0, ())
    with pytest.raises(ImporterError):
        serialize_track(_import(bad_dur))
    bad_neg = _record("T", ("A",), (_KATE_A,), _REC1, -5, ())
    with pytest.raises(ImporterError):
        serialize_track(_import(bad_neg))
    bad_title = _record("   ", ("A",), (_KATE_A,), _REC1, 1000, ())
    with pytest.raises(ImporterError):
        serialize_track(_import(bad_title))
    bad_isrc = _record("T", ("A",), (_KATE_A,), _REC1, 1000, ("NOPE",))
    with pytest.raises(ImporterError):
        serialize_track(_import(bad_isrc))
    with pytest.raises(ImporterError):
        serialize_track("not-a-track-import")
    # Coherence: track_id must equal the deterministic mapping.
    coherent = _import(good)
    tampered = TrackImport(
        track_id=uuid.uuid4(), record=coherent.record, external_ids=()
    )
    with pytest.raises(ImporterError):
        serialize_track(tampered)


def test_deterministic_sorted_bytes() -> None:
    first = _import(_record("B", ("A",), (_KATE_A,), _REC1, None, ()))
    second = _import(_record("A", ("B",), (_KATE_B,), _REC2, 1000, ()))
    assert serialize_sample_jsonl([first, second]) == serialize_sample_jsonl(
        [second, first]
    )
    payload = serialize_sample_jsonl([first, second])
    assert payload.endswith(b"\n") and not payload.endswith(b"\n\n")
    rows = [json.loads(line) for line in payload.decode("utf-8").splitlines()]
    assert [r["track_id"] for r in rows] == sorted(r["track_id"] for r in rows)
    third = _import(_record("Café del Mar", ("Beyoncé",), (_REC3,), _REC3, None, ()))
    raw = serialize_sample_jsonl([third])
    assert "Café del Mar".encode("utf-8") in raw


def test_atomic_write_budget_and_finalization(tmp_path: Path) -> None:
    tracks = serialize_sample_jsonl(
        [_import(_record("T", ("A",), (_KATE_A,), _REC1, 1000, ()))]
    )
    coverage = serialize_coverage({"v": 1})
    outdir = tmp_path / "sample-1000-v2"
    tracks_path, coverage_path = write_sample_output(outdir, tracks, coverage)
    assert tracks_path.read_bytes() == tracks
    assert coverage_path.read_bytes() == coverage
    assert tracks_path.parent == outdir and outdir.is_dir()
    assert not (tmp_path / "sample-1000-v2.staging").exists()
    with pytest.raises(ImporterError):
        write_sample_output(outdir, tracks, coverage, max_bytes=0)
    with pytest.raises(ImporterError):
        serialize_coverage("not-a-mapping")


def test_publish_refuses_existing_final_and_stale_staging(tmp_path: Path) -> None:
    """Failure paths: no overwrites, no partial visible result."""
    tracks = serialize_sample_jsonl(
        [_import(_record("T", ("A",), (_KATE_A,), _REC1, 1000, ()))]
    )
    coverage = serialize_coverage({"v": 1})
    # Budget breach finalizes nothing.
    with pytest.raises(ImporterError):
        write_sample_output(tmp_path / "a", b"x" * 10, b"y" * 10, max_bytes=5)
    assert not (tmp_path / "a").exists()
    assert not (tmp_path / "a.staging").exists()
    # Existing final directory is never overwritten.
    target = tmp_path / "b"
    target.mkdir()
    sentinel = target / "tracks.jsonl"
    sentinel.write_bytes(b"old")
    with pytest.raises(ImporterError):
        write_sample_output(target, tracks, coverage)
    assert sentinel.read_bytes() == b"old"
    assert sorted(p.name for p in target.iterdir()) == ["tracks.jsonl"]
    # Existing final FILE also refuses.
    target_file = tmp_path / "c"
    target_file.write_bytes(b"old")
    with pytest.raises(ImporterError):
        write_sample_output(target_file, tracks, coverage)
    assert target_file.read_bytes() == b"old"
    # Stale staging directory refuses (clean only after review).
    (tmp_path / "d.staging").mkdir()
    with pytest.raises(ImporterError):
        write_sample_output(tmp_path / "d", tracks, coverage)
    assert not (tmp_path / "d").exists()


def test_fixture_tar_serializes_to_v2(tmp_path: Path) -> None:
    """End-to-end on synthetic tar: real run_sample output carries v2 fields."""
    tar_path = tmp_path / "mbdump_sample.tar.bz2"
    with tarfile.open(tar_path, mode="w:bz2") as handle:
        for table in SAMPLE_TABLES:
            data = (_FIXTURES / table).read_bytes()
            info = tarfile.TarInfo(name=f"mbdump/{table}")
            info.size = len(data)
            info.mtime = 0
            handle.addfile(info, io.BytesIO(data))
    result = run_sample(tar_path, artist_limit=10)
    payload = serialize_sample_jsonl(result.imports)
    rows = [json.loads(line) for line in payload.decode("utf-8").splitlines()]
    assert rows
    for row in rows:
        assert set(row) == set(TRACK_FIELDS_V2)
        assert len(row["artists"]) == len(row["artist_mbids"]) >= 1
    duo = next(r for r in rows if r["title"] == "Duo Song")
    assert duo["artists"] == ["Arctic Monkeys", "Björk"]
    assert len(duo["artist_mbids"]) == 2
    assert duo["artist_mbids"][0] != duo["artist_mbids"][1]
