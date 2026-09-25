"""Loader CLI tests: refusal paths, bundle verification, dry-run safety.

No live database: Postgres is never contacted (dry-run opens no
connection — proven by a poisoned create_engine). The --execute write
path runs against SQLite with an ATTACHed music schema. No Meili,
container, deploy, or archive.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.staging.loader import LoaderError
from remanence.music.staging.models import MusicBase, StagedTrack

_CLI_PATH = (
    Path(__file__).resolve().parents[1] / "scripts" / "music_load_staging.py"
)


def _cli():
    import sys

    spec = importlib.util.spec_from_file_location("music_load_staging", _CLI_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["music_load_staging"] = module
    spec.loader.exec_module(module)
    return module


_T1 = "11111111-1111-4111-8111-111111111111"
_T2 = "22222222-2222-4222-8222-222222222222"
_M1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
_M2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
_A1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
_A2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

_V2_ROW = {
    "track_id": _T1,
    "title": "Spirit Number",
    "artists": ["Willy"],
    "artist_mbids": [_A1],
    "recording_mbid": _M1,
    "isrcs": ["JPM200400150"],
    "duration_ms": 253000,
}
_V1_ROW = {
    "track_id": _T2,
    "title": "Old Shape",
    "artists": ["TaQ"],
    "recording_mbid": _M2,
    "isrcs": [],
}


def _write_bundle(
    tmp_path: Path,
    rows: list[dict],
    *,
    version: object = "v2",
    tamper_sha: bool = False,
    tamper_count: bool = False,
    name: str = "bundle",
) -> Path:
    directory = tmp_path / name
    directory.mkdir()
    lines = [json.dumps(row, ensure_ascii=False) for row in rows]
    tracks = ((("\n".join(lines) + "\n") if lines else "").encode("utf-8"))
    (directory / "tracks.jsonl").write_bytes(tracks)
    digest = hashlib.sha256(tracks).hexdigest()
    coverage = {
        "schema_version": version,
        "output_sha256": "0" * 64 if tamper_sha else digest,
        "output_records": (len(lines) + 1) if tamper_count else len(lines),
    }
    (directory / "coverage.json").write_text(
        json.dumps(coverage), encoding="utf-8"
    )
    return directory


def test_verify_bundle_ok() -> None:
    cli = _cli()
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        directory = _write_bundle(Path(tmp), [_V2_ROW])
        report = cli.verify_bundle(directory)
        assert report.track_lines == 1 and report.rows_valid == 0
        assert report.schema_version == "v2"
        assert report.sha256 == hashlib.sha256(
            (directory / "tracks.jsonl").read_bytes()
        ).hexdigest()


def test_verify_bundle_refusals(tmp_path: Path) -> None:
    cli = _cli()
    with pytest.raises(cli.BundleError):
        cli.verify_bundle(tmp_path / "absent")
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(cli.BundleError):
        cli.verify_bundle(empty)
    directory = _write_bundle(tmp_path, [_V2_ROW])
    (directory / "coverage.json").unlink()
    with pytest.raises(cli.BundleError):
        cli.verify_bundle(directory)
    bad_version = _write_bundle(tmp_path, [_V2_ROW], version="v1", name="badver")
    with pytest.raises(cli.BundleError):
        cli.verify_bundle(bad_version)
    bad_sha = _write_bundle(tmp_path, [_V2_ROW], tamper_sha=True, name="badsha")
    with pytest.raises(cli.BundleError):
        cli.verify_bundle(bad_sha)
    bad_count = _write_bundle(tmp_path, [_V2_ROW], tamper_count=True, name="badcount")
    with pytest.raises(cli.BundleError):
        cli.verify_bundle(bad_count)


def test_dry_run_validates_and_rejects_v1_rows(tmp_path: Path) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW, _V1_ROW])
    report = cli.validate_bundle_rows(directory)
    assert report.rows_valid == 1 and report.rows_invalid == 1
    assert report.invalid_reasons == {"missing_artist_mbids": 1}


def test_main_dry_run_opens_no_connection(tmp_path: Path, monkeypatch, capsys) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW])

    def _poison(*args, **kwargs):
        raise AssertionError("database connection attempted during dry-run")

    monkeypatch.setattr(cli, "create_engine", _poison)
    code = cli.main(
        [
            "--database-url",
            "postgresql+psycopg://u:pw@127.0.0.1:1/remanence",
            "--sample-dir",
            str(directory),
        ]
    )
    assert code == 0
    out = capsys.readouterr().out
    assert "dry-run" in out and "pw@" not in out and ":pw" not in out
    assert "127.0.0.1" in out


def test_main_refuses_non_postgres_scheme(tmp_path: Path, capsys) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW])
    code = cli.main(
        ["--database-url", "sqlite:///tmp/x.db", "--sample-dir", str(directory)]
    )
    assert code == 2
    assert "postgresql+psycopg" in capsys.readouterr().out


def test_main_requires_database_url() -> None:
    cli = _cli()
    with pytest.raises(SystemExit) as exc:
        cli.main(["--sample-dir", "/tmp"])
    assert exc.value.code == 2


def test_main_dry_run_refuses_bad_bundle(tmp_path: Path) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW], tamper_sha=True)
    code = cli.main(
        [
            "--database-url",
            "postgresql+psycopg://u@127.0.0.1:55432/remanence",
            "--sample-dir",
            str(directory),
        ]
    )
    assert code == 2


def _sqlite_music_engine():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as connection:
        connection.execute(text("ATTACH DATABASE ':memory:' AS music"))
    MusicBase.metadata.create_all(engine)
    return engine


def test_run_load_writes_and_reloads_idempotently(tmp_path: Path) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW])
    engine = _sqlite_music_engine()
    report, stats = cli.run_load(engine, directory)
    assert stats.tracks_merged == 1 and stats.rows_invalid == 0
    with Session(engine) as session:
        count = session.scalar(select(func.count()).select_from(StagedTrack))
    assert count == 1
    report2, stats2 = cli.run_load(engine, directory)
    assert stats2.tracks_merged == 1
    with Session(engine) as session:
        assert session.scalar(select(func.count()).select_from(StagedTrack)) == 1
    assert report.schema_version == report2.schema_version == "v2"


def test_run_load_refuses_missing_schema(tmp_path: Path) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW])
    engine = create_engine("sqlite://")
    with pytest.raises(cli.BundleError):
        cli.run_load(engine, directory)


def test_run_load_rejects_bad_batch_rows(tmp_path: Path) -> None:
    cli = _cli()
    directory = _write_bundle(tmp_path, [_V2_ROW])
    engine = _sqlite_music_engine()
    with pytest.raises(LoaderError):
        cli.run_load(engine, directory, batch_rows=0)


def test_migration_chain_single_head_at_0009() -> None:
    """DB-free: linear chain, exactly one head, revision ledger on top."""
    import importlib.util

    versions = Path(__file__).resolve().parent.parent / "migrations" / "versions"
    modules: dict[str, object] = {}
    for path in sorted(versions.glob("0*.py")):
        spec = importlib.util.spec_from_file_location(f"mig_{path.stem}", path)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        modules[module.revision] = module
    assert modules, "no migration revisions found"
    children: dict[str, str] = {}
    for revision, module in modules.items():
        parent = module.down_revision
        if parent is not None:
            assert parent in modules, f"dangling down_revision: {parent}"
            assert parent not in children, f"branch at {parent}"
            children[parent] = revision
    heads = [revision for revision in modules if revision not in children]
    assert heads == ["0009_music_index_revisions"]
    ledger = modules["0009_music_index_revisions"]
    assert ledger.down_revision == "0008_music_staging"
    music = modules["0008_music_staging"]
    assert music.down_revision == "0007_m2_f3_first_open_claim"
