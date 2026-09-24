"""Importer slice tests: streaming mbdump parser, normalizer, identity.

Uses SMALL synthetic COPY fixtures packed into tar.bz2 in tmp_path — no
real archive, no network, no database. Asserts hard caps, deterministic
mapping, and explicit coverage metadata. Never touches product search.
"""

from __future__ import annotations

import ast
import io
import tarfile
import uuid
from pathlib import Path

import pytest

from remanence.music.domain import ExternalIdentifier
from remanence.music.ingestion import (
    ImporterError,
    ImporterLimitError,
    assign_track_ids,
    build_allowlist,
    normalize_tables,
    parse_copy_line,
    parse_member_rows,
    parse_tables,
    probe_member_sizes,
    run_sample,
    track_id_for_recording,
    unescape_copy_field,
)
from remanence.music.ingestion.identity import NAMESPACE_REMANENCE_MUSICBRAINZ_V1
from remanence.music.ingestion.limits import (
    CANDIDATE_ID_BYTES,
    MAX_CANDIDATE_BYTES,
    MAX_CANDIDATE_CREDITS,
    SAMPLE_TABLES,
)
from remanence.music.ingestion.mbdump import TABLE_SCHEMAS
from remanence.music.ingestion.normalize import SAMPLE_METHOD, NormalizedRecord
from remanence.music.ingestion.sample import SAMPLE_PASSES, SampleResult

_FIXTURES = Path(__file__).resolve().parent / "fixtures" / "music" / "mbdump_sample"


def _pack_sample_tar(tmp_path: Path, extra: tuple[tuple[str, bytes], ...] = ()) -> Path:
    tar_path = tmp_path / "mbdump_sample.tar.bz2"
    with tarfile.open(tar_path, mode="w:bz2") as handle:
        for table in SAMPLE_TABLES:
            data = (_FIXTURES / table).read_bytes()
            info = tarfile.TarInfo(name=f"mbdump/{table}")
            info.size = len(data)
            info.mtime = 0
            handle.addfile(info, io.BytesIO(data))
        for name, data in extra:
            info = tarfile.TarInfo(name=name)
            info.size = len(data)
            info.mtime = 0
            handle.addfile(info, io.BytesIO(data))
    return tar_path

def test_parse_tables_reads_copy_format(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    tables, report = parse_tables(tar_path)
    assert set(tables) == set(SAMPLE_TABLES)
    assert report.members_parsed == len(SAMPLE_TABLES)
    assert report.tar_name == "mbdump_sample.tar.bz2"

    artists = tables["artist"]
    assert len(artists) == 4
    assert artists[0] == {
        "id": 1,
        "gid": "8c78827f-25d2-5e60-826a-aa77fb5ac1cd",
        "name": "Arctic Monkeys",
    }
    # Trailing dump columns are ignored; escaped tab is decoded.
    assert artists[3]["name"] == "A\tB"
    # UTF-8 names survive the stream.
    assert artists[1]["name"] == "Кино"

    stats = {s.table: s for s in report.tables}
    assert (stats["artist"].rows_seen, stats["artist"].rows_malformed) == (5, 1)
    assert (
        stats["artist_credit_name"].rows_seen,
        stats["artist_credit_name"].rows_malformed,
    ) == (5, 1)
    assert len(tables["recording"]) == 9
    assert len(tables["isrc"]) == 7


def test_null_and_escapes() -> None:
    assert unescape_copy_field("\\N") is None
    assert unescape_copy_field("") == ""
    assert unescape_copy_field("A\\tB") == "A\tB"
    assert unescape_copy_field("C\\\\D") == "C\\D"
    # COPY octal (1-3 digits) and hex escapes.
    assert unescape_copy_field("\\101") == "A"
    assert unescape_copy_field("\\141\\142") == "ab"
    assert unescape_copy_field("\\x42") == "B"
    assert unescape_copy_field("\\x4a\\x4B") == "JK"
    # Unknown escapes pass through with the backslash dropped.
    assert unescape_copy_field("\\q") == "q"
    assert unescape_copy_field("100\\%") == "100%"
    # Trailing lone backslash is kept literally.
    assert unescape_copy_field("AB\\") == "AB\\"
    row = parse_copy_line(
        "101\t0a92f98a-770d-52cc-8766-03415fdcb2d4\t505\t10\t\\N",
        TABLE_SCHEMAS["recording"],
    )
    assert row is not None and row["length"] is None
    assert parse_copy_line("1\t2", TABLE_SCHEMAS["artist"]) is None
    assert parse_copy_line("x\ty\tz\t1\tq", TABLE_SCHEMAS["recording"]) is None


def test_artist_credit_name_official_layout() -> None:
    """Audit STOP: artist_credit, position, artist, name, join_phrase."""
    schema = TABLE_SCHEMAS["artist_credit_name"]
    row = parse_copy_line("12\t1\t3\tBjörk\t", schema)
    assert row == {
        "artist_credit": 12,
        "position": 1,
        "artist": 3,
        "name": "Björk",
        "join_phrase": "",
    }
    # join_phrase may be absent (min 4 columns).
    short = parse_copy_line("11\t0\t2\tКино", schema)
    assert short is not None and short["join_phrase"] is None
    assert short["artist"] == 2 and short["position"] == 0
    # Old (wrong) layout would read artist=1 here; position pins it to 2.
    assert parse_copy_line("1\t2", schema) is None


def test_unknown_members_skipped_and_counted(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(
        tmp_path, extra=(("mbdump/README", b"notes\n"), ("TIMESTAMP", b"2026\n"))
    )
    _, report = parse_tables(tar_path)
    assert report.members_skipped_unknown == 2
    assert report.members_seen == len(SAMPLE_TABLES) + 2


def test_unsafe_tar_member_rejected(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path, extra=(("../evil", b"x\n"),))
    with pytest.raises(ImporterError):
        parse_tables(tar_path)


def test_missing_required_member(tmp_path: Path) -> None:
    tar_path = tmp_path / "thin.tar.bz2"
    with tarfile.open(tar_path, mode="w:bz2") as handle:
        data = (_FIXTURES / "artist").read_bytes()
        info = tarfile.TarInfo(name="mbdump/artist")
        info.size = len(data)
        handle.addfile(info, io.BytesIO(data))
    with pytest.raises(ImporterError):
        parse_tables(tar_path)
    with pytest.raises(ImporterError):
        parse_member_rows(tar_path, "isrc")


def test_row_cap_enforced(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterLimitError):
        parse_member_rows(tar_path, "recording", row_cap=2)


def test_member_byte_cap_enforced(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterLimitError):
        parse_member_rows(tar_path, "artist", member_byte_cap=10)


def test_retained_caps_enforced_fail_closed(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterLimitError):
        parse_member_rows(tar_path, "recording", retained_cap=2)
    with pytest.raises(ImporterLimitError):
        parse_member_rows(tar_path, "recording", retained_byte_cap=10)


def test_keep_predicate_filters_while_streaming(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    rows, stats = parse_member_rows(
        tar_path,
        "recording",
        keep=lambda row: row.get("artist_credit") == 10,
    )
    assert stats.rows_seen == 9
    assert stats.rows_kept == 6
    assert stats.rows_malformed == 0
    assert {r["id"] for r in rows} == {100, 101, 105, 106, 107, 108}


def test_malformed_cap_enforced(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterLimitError):
        parse_member_rows(tar_path, "artist", malformed_cap=0)


def test_missing_tar_file(tmp_path: Path) -> None:
    with pytest.raises(ImporterError):
        run_sample(tmp_path / "absent.tar.bz2")


def _parsed(tmp_path: Path):
    tables, _ = parse_tables(_pack_sample_tar(tmp_path))
    return tables


def test_normalize_join_and_allowlist(tmp_path: Path) -> None:
    records, stats = normalize_tables(_parsed(tmp_path), artist_limit=10)
    assert stats.artists_seen == 4 and stats.artists_kept == 4
    assert stats.recordings_seen == 9
    assert stats.recordings_kept == 6
    assert stats.dropped_unresolvable_credit == 1
    assert stats.dropped_empty_title == 1
    assert stats.dropped_bad_mbid == 1
    assert stats.dropped_outside_allowlist == 0
    assert stats.negative_length_sanitized == 1
    by_mbid = {}
    for r in records:
        by_mbid.setdefault(r.recording_mbid, r)
    first = by_mbid["bd4b32c8-e048-591f-908b-ef5eaaa756a6"]
    assert first.title == "505"
    assert first.artists == ("Arctic Monkeys",)
    assert first.duration_ms == 253000
    assert first.recording_mbid == "bd4b32c8-e048-591f-908b-ef5eaaa756a6"
    assert first.isrcs == ("GBAYE0700505",)
    null_length = [r for r in records if r.recording_mbid.startswith("0a92f98a")]
    assert len(null_length) == 1 and null_length[0].duration_ms is None
    neg = [r for r in records if r.title == "Neg"]
    assert len(neg) == 1 and neg[0].duration_ms is None
    duo = next(r for r in records if r.title == "Duo Song")
    assert duo.artists == ("Arctic Monkeys", "Björk")
    assert stats.isrcs_seen == 7
    assert stats.isrcs_kept == 3
    assert stats.isrcs_invalid == 1
    assert stats.isrcs_orphan == 2


def test_allowlist_is_hash_deterministic_and_truncates(tmp_path: Path) -> None:
    tables = _parsed(tmp_path)
    first, stats_first = normalize_tables(tables, artist_limit=2)
    second, _ = normalize_tables(tables, artist_limit=2)
    assert [r.recording_mbid for r in first] == [r.recording_mbid for r in second]
    # Hash rank over artist:<id> orders 2,3,4,1 — NOT first-N-by-id {1,2}.
    # Documented bias: uniform over the id keyspace, not representative.
    allowlist, selected = build_allowlist(
        tables["artist"], tables["artist_credit_name"], artist_limit=2
    )
    assert allowlist == frozenset({2, 3})
    assert selected == frozenset({11})
    assert stats_first.artists_kept == 2
    assert stats_first.dropped_outside_allowlist == 5
    assert {r.title for r in first} == {"Группа крови"}


def test_hash_topn_keeps_smallest_ranks_not_largest(tmp_path: Path) -> None:
    """Regression: the streaming heap must keep hash-SMALLEST artists."""
    from remanence.music.ingestion.sample import _HashTopN

    tables, _ = parse_tables(_pack_sample_tar(tmp_path), ("artist",))
    topn = _HashTopN(2)
    for row in tables["artist"]:
        assert topn(row) is False
    kept = {r["id"] for r in topn.rows()}
    # Hash order is 2,3,4,1 — a min/max mix-up would keep {4,1} instead.
    assert kept == {2, 3}


def test_build_allowlist_never_splits_partial_credits() -> None:
    artists = [
        {"id": 1, "gid": "8c78827f-25d2-5e60-826a-aa77fb5ac1cd", "name": "A"},
        {"id": 2, "gid": "310f28db-37b9-51a9-91f8-4fa98896eef6", "name": "B"},
    ]
    credits = [
        {"artist_credit": 10, "position": 0, "artist": 1, "name": "A", "join_phrase": ""},
        {"artist_credit": 12, "position": 0, "artist": 1, "name": "A", "join_phrase": " & "},
        {"artist_credit": 12, "position": 1, "artist": 3, "name": "C", "join_phrase": ""},
    ]
    allowlist, selected = build_allowlist(artists, credits, artist_limit=10)
    assert allowlist == frozenset({1, 2})
    # Credit 12 needs artist 3 (outside): excluded whole, not split.
    assert selected == frozenset({10})


def test_track_ids_deterministic_and_not_mbids(tmp_path: Path) -> None:
    records, _ = normalize_tables(_parsed(tmp_path), artist_limit=10)
    first, dup_first = assign_track_ids(records)
    second, dup_second = assign_track_ids(records)
    assert dup_first == dup_second == 1
    assert [t.track_id for t in first] == [t.track_id for t in second]
    for item in first:
        assert type(item.track_id) is uuid.UUID
        assert item.track_id.version == 5
        assert str(item.track_id) != item.record.recording_mbid
    expected = uuid.uuid5(
        NAMESPACE_REMANENCE_MUSICBRAINZ_V1,
        "musicbrainz:recording:bd4b32c8-e048-591f-908b-ef5eaaa756a6",
    )
    assert track_id_for_recording("BD4B32C8-E048-591F-908B-EF5EAAA756A6") == expected
    with pytest.raises(ValueError):
        track_id_for_recording("not-a-uuid")


def test_external_ids_attached_and_valid(tmp_path: Path) -> None:
    records, _ = normalize_tables(_parsed(tmp_path), artist_limit=10)
    imports, _ = assign_track_ids(records)
    # mbid bd4b… occurs twice (100 + repress 107); take the 253s studio row.
    item = next(
        i
        for i in imports
        if i.record.recording_mbid == "bd4b32c8-e048-591f-908b-ef5eaaa756a6"
        and i.record.duration_ms == 253000
    )
    namespaces = [e.namespace for e in item.external_ids]
    assert "musicbrainz_recording" in namespaces
    assert "isrc" in namespaces
    assert all(isinstance(e, ExternalIdentifier) for e in item.external_ids)
    assert all(e.track_id == item.track_id for e in item.external_ids)


def test_run_sample_end_to_end_and_coverage(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    result = run_sample(tar_path, artist_limit=10)
    assert isinstance(result, SampleResult)
    assert len(result.imports) == 6
    coverage = result.coverage.to_dict()
    assert coverage["tar_name"] == "mbdump_sample.tar.bz2"
    assert coverage["artist_limit"] == 10
    assert coverage["sample_method"] == SAMPLE_METHOD == "hash-sha256-ascending"
    assert coverage["passes"] == SAMPLE_PASSES == 5
    assert "positional map v1" in coverage["schema_note"]
    assert coverage["artists_kept"] == 4
    assert coverage["recordings_kept"] == 6
    assert coverage["duplicate_mbid_collapses"] == 1
    assert coverage["isrc_collisions"] == 1
    assert coverage["caps"]["row_cap"] > 0
    assert coverage["caps"]["retained_cap"] > 0
    assert coverage["caps"]["retained_byte_cap"] > 0
    assert any("malformed_rows=1" in flag for flag in coverage["limits_hit"])
    assert {t["table"] for t in coverage["tables"]} == set(SAMPLE_TABLES)

    small = run_sample(tar_path, artist_limit=2)
    assert small.coverage.recordings_kept == 1
    assert small.coverage.artists_kept == 2
    assert {i.record.title for i in small.imports} == {"Группа крови"}
    assert any("artist_truncated" in flag for flag in small.coverage.limits_hit)


def test_ingestion_has_no_product_search_surface() -> None:
    """AST-level guard: ingestion must not import search/api/db runtimes."""
    package = Path(__file__).resolve().parent.parent / "src" / "remanence" / "music" / "ingestion"
    forbidden = (
        "remanence.music.search",
        "remanence.api",
        "remanence.main",
        "sqlalchemy",
        "fastapi",
    )
    for module in sorted(package.glob("*.py")):
        tree = ast.parse(module.read_text(encoding="utf-8"))
        imported: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module:
                imported.append(node.module)
            elif isinstance(node, ast.Import):
                imported.extend(a.name for a in node.names)
        for name in imported:
            assert not name.startswith(forbidden), (module.name, name)


def test_candidate_count_cap_trips_fail_closed(tmp_path: Path) -> None:
    """B2: more candidate credits than the cap aborts, never truncates."""
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterLimitError):
        run_sample(tar_path, artist_limit=10, candidate_cap=1)


def test_candidate_byte_cap_trips_fail_closed(tmp_path: Path) -> None:
    """B2: estimated candidate bytes over budget aborts as well."""
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterLimitError):
        run_sample(tar_path, artist_limit=10, candidate_byte_cap=1)


def test_candidate_caps_invalid_rejected(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterError):
        run_sample(tar_path, artist_limit=10, candidate_cap=0)
    with pytest.raises(ImporterError):
        run_sample(tar_path, artist_limit=10, candidate_byte_cap=-5)


def test_candidate_caps_recorded_in_coverage(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path)
    result = run_sample(tar_path, artist_limit=10)
    assert result.coverage.candidate_cap == MAX_CANDIDATE_CREDITS
    assert result.coverage.candidate_byte_cap == MAX_CANDIDATE_BYTES
    assert MAX_CANDIDATE_BYTES == MAX_CANDIDATE_CREDITS * CANDIDATE_ID_BYTES
    assert result.coverage.to_dict()["caps"]["candidate_cap"] == MAX_CANDIDATE_CREDITS


def test_probe_member_sizes_lists_declared_bytes(tmp_path: Path) -> None:
    """B1: read-only header probe reports per-member sizes, parses nothing."""
    tar_path = _pack_sample_tar(tmp_path)
    sizes = probe_member_sizes(tar_path)
    assert set(sizes) == {f"mbdump/{t}" for t in SAMPLE_TABLES}
    assert all(type(v) is int and v > 0 for v in sizes.values())
    for table in SAMPLE_TABLES:
        expected = (_FIXTURES / table).stat().st_size
        assert sizes[f"mbdump/{table}"] == expected


def test_probe_rejects_unsafe_member(tmp_path: Path) -> None:
    tar_path = _pack_sample_tar(tmp_path, extra=(("../evil", b"x\n"),))
    with pytest.raises(ImporterError):
        probe_member_sizes(tar_path)


def test_guarded_raise_path_accepts_measured_size(tmp_path: Path) -> None:
    """B1: an explicitly measured cap works; below it still trips."""
    tar_path = _pack_sample_tar(tmp_path)
    sizes = probe_member_sizes(tar_path)
    measured = sizes["mbdump/recording"]
    ok = run_sample(tar_path, artist_limit=10, member_byte_cap=measured)
    assert ok.coverage.recordings_kept == 6
    with pytest.raises(ImporterLimitError):
        run_sample(tar_path, artist_limit=10, member_byte_cap=measured - 1)


def test_member_byte_cap_must_stay_positive(tmp_path: Path) -> None:
    """B1: the bound can be raised, never disabled."""
    tar_path = _pack_sample_tar(tmp_path)
    with pytest.raises(ImporterError):
        run_sample(tar_path, artist_limit=10, member_byte_cap=0)
    with pytest.raises(ImporterError):
        run_sample(tar_path, artist_limit=10, member_byte_cap=-1)
