"""Staging loader tests: schema contract + idempotent JSONL loads.

Artist identity is MBID-derived (homonym-safe): same display name with
different MBIDs yields distinct rows; v1 rows without artist_mbids are
rejected outright. SQLite-backed via schema_translate_map (music.* ->
main.*). No live Postgres, no migrations executed, no search index, no
endpoints.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session

from remanence.music.staging.loader import (
    ARTIST_MBID_NAMESPACE_V1,
    LoadStats,
    LoaderError,
    RowInvalid,
    artist_id_for_mbid,
    load_staged_jsonl,
    validate_row,
)
from remanence.music.search.revision_store import (  # noqa: F401
    IndexActivation,
    IndexRevision,
)
from remanence.music.staging.models import (
    MUSIC_SCHEMA,
    MusicBase,
    StagedArtist,
    StagedExternalId,
    StagedTrack,
    StagedTrackArtist,
)

_T1 = "11111111-1111-4111-8111-111111111111"
_T2 = "22222222-2222-4222-8222-222222222222"
_M1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
_M2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
_A1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
_A2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
_A3 = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"


def _session() -> Session:
    engine = create_engine(
        "sqlite://", execution_options={"schema_translate_map": {"music": None}}
    )
    MusicBase.metadata.create_all(engine)
    return Session(engine)


def _write_jsonl(tmp_path: Path, rows: list[object]) -> Path:
    path = tmp_path / "sample.jsonl"
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            if isinstance(row, str):
                handle.write(row + "\n")
            else:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return path


def _row(
    track_id: str,
    title: str,
    artists: list[str],
    artist_mbids: list[str],
    mbid: str,
    isrcs: list[str],
) -> dict:
    return {
        "track_id": track_id,
        "title": title,
        "artists": artists,
        "artist_mbids": artist_mbids,
        "recording_mbid": mbid,
        "isrcs": isrcs,
    }


def _counts(session: Session) -> dict[str, int]:
    return {
        "tracks": session.scalar(select(func.count()).select_from(StagedTrack)),
        "artists": session.scalar(select(func.count()).select_from(StagedArtist)),
        "credits": session.scalar(select(func.count()).select_from(StagedTrackArtist)),
        "external": session.scalar(select(func.count()).select_from(StagedExternalId)),
    }


def test_schema_contract_matches_architecture() -> None:
    assert MUSIC_SCHEMA == "music"
    tables = MusicBase.metadata.tables
    assert set(tables) == {
        "music.music_track",
        "music.music_artist",
        "music.music_track_artist",
        "music.music_external_id",
        "music.music_index_revision",
        "music.music_index_activation",
    }
    assert [c.name for c in tables["music.music_track"].columns] == [
        "id", "title", "normalized_title", "duration_ms", "variant",
        "first_release_year", "status", "created_at", "updated_at",
    ]
    assert [c.name for c in tables["music.music_artist"].columns] == [
        "id", "name", "normalized_name"
    ]
    assert [c.name for c in tables["music.music_track_artist"].columns] == [
        "track_id", "artist_id", "position"
    ]
    assert [c.name for c in tables["music.music_external_id"].columns] == [
        "track_id", "namespace", "external_id", "source", "confidence", "active"
    ]
    checks = " ".join(
        str(c.sqltext)
        for table in tables.values()
        for c in table.constraints
        if c.__class__.__name__ == "CheckConstraint"
    )
    assert "duration_ms IS NULL OR duration_ms > 0" in checks
    # No global name-uniqueness: homonyms are legal rows, looked up by index.
    unique_on_name = [
        c for c in tables["music.music_artist"].constraints
        if c.__class__.__name__ == "UniqueConstraint"
    ]
    assert unique_on_name == []
    indexes = {index.name for index in tables["music.music_artist"].indexes}
    assert "ix_music_artist_normalized_name" in indexes


def test_artist_identity_is_mbid_derived() -> None:
    first = artist_id_for_mbid(_A1)
    assert first == artist_id_for_mbid(_A1.upper())
    assert first == uuid.uuid5(ARTIST_MBID_NAMESPACE_V1, f"musicbrainz:artist:{_A1}")
    assert str(first) != _A1
    assert artist_id_for_mbid(_A2) != first
    with pytest.raises(ValueError):
        artist_id_for_mbid("not-a-uuid")
    with pytest.raises(ValueError):
        artist_id_for_mbid("")


def test_homonyms_stay_separate_rows(tmp_path: Path) -> None:
    """Same display name, different MBIDs: two artist rows, correct links."""
    session = _session()
    file_path = _write_jsonl(
        tmp_path,
        [
            _row(_T1, "Song One", ["Kate"], [_A1], _M1, []),
            _row(_T2, "Song Two", ["Kate"], [_A2], _M2, []),
        ],
    )
    stats = load_staged_jsonl(session, file_path)
    assert stats.rows_valid == 2 and stats.rows_invalid == 0
    assert stats.artists_seen == 2
    assert _counts(session)["artists"] == 2
    kates = session.scalars(
        select(StagedArtist).where(StagedArtist.normalized_name == "kate")
    ).all()
    assert len(kates) == 2
    assert {kate.id for kate in kates} == {
        artist_id_for_mbid(_A1), artist_id_for_mbid(_A2)
    }
    link1 = session.scalars(
        select(StagedTrackArtist).where(StagedTrackArtist.track_id == uuid.UUID(_T1))
    ).all()
    link2 = session.scalars(
        select(StagedTrackArtist).where(StagedTrackArtist.track_id == uuid.UUID(_T2))
    ).all()
    assert [link.artist_id for link in link1] == [artist_id_for_mbid(_A1)]
    assert [link.artist_id for link in link2] == [artist_id_for_mbid(_A2)]


def test_load_valid_minimal(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(
        tmp_path,
        [
            _row(_T1, "Spirit Number", ["Willy"], [_A1], _M1, ["JPM200400150"]),
            _row(_T2, "Innocent Walls", ["Willy", "TaQ"], [_A1, _A3], _M2,
                 ["JPM200400150"]),
        ],
    )
    stats = load_staged_jsonl(session, file_path)
    assert isinstance(stats, LoadStats)
    assert stats.rows_valid == 2 and stats.rows_invalid == 0
    assert stats.tracks_merged == 2 and stats.track_repeats == 0
    assert stats.isrc_collision_pairs == 1
    assert stats.artists_seen == 2
    assert _counts(session) == {"tracks": 2, "artists": 2, "credits": 3, "external": 4}

    willy = session.get(StagedArtist, artist_id_for_mbid(_A1))
    assert willy is not None and willy.name == "Willy"
    credits = session.scalars(
        select(StagedTrackArtist)
        .where(StagedTrackArtist.track_id == uuid.UUID(_T2))
        .order_by(StagedTrackArtist.position)
    ).all()
    assert [(c.artist_id, c.position) for c in credits] == [
        (artist_id_for_mbid(_A1), 0), (artist_id_for_mbid(_A3), 1)
    ]

    links = session.scalars(
        select(StagedExternalId).where(StagedExternalId.namespace == "isrc")
    ).all()
    assert sorted(link.external_id for link in links) == ["JPM200400150", "JPM200400150"]
    assert {link.track_id for link in links} == {
        uuid.UUID(_T1), uuid.UUID(_T2),
    }


def test_idempotent_reload_converges(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(
        tmp_path,
        [
            _row(_T1, "Spirit Number", ["Willy"], [_A1], _M1, ["JPM200400150"]),
            _row(_T2, "Innocent Walls", ["TaQ"], [_A3], _M2, []),
        ],
    )
    first = load_staged_jsonl(session, file_path)
    before = _counts(session)
    second = load_staged_jsonl(session, file_path)
    assert _counts(session) == before
    assert (first.rows_valid, second.rows_valid) == (2, 2)
    assert second.track_repeats == 0


def test_duplicate_mbid_first_wins(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(
        tmp_path,
        [
            _row(_T1, "Spirit Number", ["Willy"], [_A1], _M1, []),
            _row(_T1, "CHANGED TITLE", ["Other"], [_A3], _M1, ["USABC1234567"]),
        ],
    )
    stats = load_staged_jsonl(session, file_path)
    assert stats.track_repeats == 1
    track = session.get(StagedTrack, uuid.UUID(_T1))
    assert track is not None and track.title == "Spirit Number"
    assert _counts(session)["tracks"] == 1


def test_v1_rows_without_artist_mbids_rejected(tmp_path: Path) -> None:
    """STOP condition: old v1 shape is refused, never name-merged."""
    session = _session()
    v1_row = {
        "track_id": _T1, "title": "Spirit Number", "artists": ["Willy"],
        "recording_mbid": _M1, "isrcs": [],
    }
    file_path = _write_jsonl(tmp_path, [v1_row])
    stats = load_staged_jsonl(session, file_path)
    assert stats.rows_valid == 0 and stats.rows_invalid == 1
    assert stats.invalid_reasons == {"missing_artist_mbids": 1}
    assert _counts(session) == {"tracks": 0, "artists": 0, "credits": 0, "external": 0}


def test_mismatched_and_bad_artist_mbids_rejected(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(
        tmp_path,
        [
            _row(_T1, "Good", ["Willy"], [_A1], _M1, []),
            {**_row(_T2, "Short", ["Willy", "TaQ"], [_A1], _M2, [])},
            {**_row(_T2, "Bad", ["Willy"], ["zzz"], _M2, [])},
            {**_row(_T2, "Type", ["Willy"], "not-a-list", _M2, [])},
        ],
    )
    stats = load_staged_jsonl(session, file_path)
    assert stats.rows_valid == 1 and stats.rows_invalid == 3
    assert stats.invalid_reasons["artist_mbid_mismatch"] == 2
    assert stats.invalid_reasons["bad_artist_mbid"] == 1
    assert _counts(session)["tracks"] == 1


def test_invalid_rows_skipped_with_reasons(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(
        tmp_path,
        [
            _row(_T1, "Good", ["Willy"], [_A1], _M1, []),
            "not json{{{",
            "",
            {"title": "x"},
            _row("not-a-uuid", "Bad", ["Willy"], [_A1], _M1, []),
            _row(_T2, "   ", ["Willy"], [_A1], _M2, []),
            _row(_T2, "Zero", ["Willy"], [_A1], _M2, []),
            {"track_id": _T2, "title": "Z", "artists": ["W"], "artist_mbids": [_A1],
             "recording_mbid": _M2, "isrcs": [], "duration_ms": 0},
            {"track_id": _T2, "title": "Z", "artists": ["W"], "artist_mbids": [_A1],
             "recording_mbid": _M2, "isrcs": ["BAD!!"]},
            {"track_id": _T2, "title": "Z", "artists": "Willy", "artist_mbids": [_A1],
             "recording_mbid": _M2, "isrcs": []},
            {"track_id": _T2, "title": "Z", "artists": ["W"], "artist_mbids": [_A1],
             "recording_mbid": "zzz", "isrcs": []},
            {"track_id": _T2, "title": 42, "artists": ["W"], "artist_mbids": [_A1],
             "recording_mbid": _M2, "isrcs": []},
        ],
    )
    stats = load_staged_jsonl(session, file_path)
    assert stats.rows_valid == 2 and stats.rows_invalid == 10
    assert stats.invalid_reasons["bad_json"] == 1
    assert stats.invalid_reasons["blank_line"] == 1
    assert stats.invalid_reasons["missing_keys"] == 1
    assert stats.invalid_reasons["bad_track_id"] == 1
    assert stats.invalid_reasons["bad_track_fields"] == 2
    assert stats.invalid_reasons["bad_duration"] == 1
    assert stats.invalid_reasons["bad_isrc"] == 1
    assert stats.invalid_reasons["bad_artists"] == 1
    assert stats.invalid_reasons["bad_recording_mbid"] == 1
    assert _counts(session)["tracks"] == 2


def test_duration_null_never_zero(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(
        tmp_path, [_row(_T1, "Spirit Number", ["Willy"], [_A1], _M1, [])]
    )
    load_staged_jsonl(session, file_path)
    track = session.get(StagedTrack, uuid.UUID(_T1))
    assert track is not None and track.duration_ms is None
    assert track.variant is None and track.first_release_year is None
    assert track.status.value == "ACTIVE"


def test_batch_boundaries_commit_all(tmp_path: Path) -> None:
    session = _session()
    rows = [
        _row(
            str(uuid.uuid5(uuid.NAMESPACE_DNS, f"track-{i}")),
            f"Title {i}",
            [f"Artist {i}"],
            [str(uuid.uuid5(uuid.NAMESPACE_DNS, f"artist-{i}"))],
            str(uuid.uuid5(uuid.NAMESPACE_DNS, f"mbid-{i}")),
            [],
        )
        for i in range(5)
    ]
    file_path = _write_jsonl(tmp_path, rows)
    stats = load_staged_jsonl(session, file_path, batch_rows=2)
    assert stats.rows_valid == 5 and stats.batches_committed == 3
    assert _counts(session)["tracks"] == 5


def test_empty_file_is_noop(tmp_path: Path) -> None:
    session = _session()
    file_path = _write_jsonl(tmp_path, [])
    stats = load_staged_jsonl(session, file_path)
    assert (stats.lines_seen, stats.rows_valid, stats.batches_committed) == (0, 0, 0)
    assert _counts(session) == {"tracks": 0, "artists": 0, "credits": 0, "external": 0}


def test_missing_file_and_bad_batch_raise(tmp_path: Path) -> None:
    session = _session()
    with pytest.raises(LoaderError):
        load_staged_jsonl(session, tmp_path / "absent.jsonl")
    file_path = _write_jsonl(
        tmp_path, [_row(_T1, "T", ["A"], [_A1], _M1, [])]
    )
    with pytest.raises(LoaderError):
        load_staged_jsonl(session, file_path, batch_rows=0)


def test_validate_row_accepts_optional_duration() -> None:
    row = validate_row(
        {
            "track_id": _T1, "title": "T", "artists": ["A"], "artist_mbids": [_A1],
            "recording_mbid": _M1, "isrcs": [], "duration_ms": None,
        }
    )
    assert row.duration_ms is None
    assert row.artist_mbids == (_A1,)
    with pytest.raises(RowInvalid):
        validate_row("just a string")
