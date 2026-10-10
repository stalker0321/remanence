"""Full disk-backed core catalog + FTS5 build; no sample, live DB or activation.

Reads a verified archive once in tar stream mode, never extracts members.
SQLite does the joins/sorts on disk. Python retains only a byte-capped batch
and one bounded ordered artist credit, not an all-catalog dict/list.
"""
from __future__ import annotations

import itertools
import json
import os
import re
import shutil
import sqlite3
import tarfile
import time
import uuid
from pathlib import Path

from remanence.music.catalog_prepare import GIB, Snapshot, digest, job_lock
from remanence.music.domain import MusicTrack, normalize_text
from remanence.music.ingestion.identity import track_id_for_recording
from remanence.music.ingestion.mbdump import TABLE_SCHEMAS, parse_copy_line
from remanence.music.search.document import VARIANT_TOKENS, build_search_document

TABLES = tuple(TABLE_SCHEMAS)
MAX_LINE = 1024 * 1024
BATCH_ROWS = 2000
BATCH_BYTES = 8 * 1024 * 1024
MAX_CREDIT_MEMBERS = 1024
ISRC = re.compile(r"[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")


class CatalogBuildError(RuntimeError):
    pass


def event(phase: str, **values):
    print(json.dumps({"utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                      "phase": phase, **values}, ensure_ascii=False), flush=True)


def atomic_json(path: Path, value):
    temp = path.with_name(path.name + ".tmp")
    with temp.open("w", encoding="utf8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.flush(); os.fsync(output.fileno())
    temp.replace(path)
    fd = os.open(path.parent, os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)


class DiskBudget:
    def __init__(self, directory: Path, max_bytes: int, reserve_bytes: int):
        self.directory, self.max_bytes, self.reserve_bytes = directory, max_bytes, reserve_bytes
        self.last_check = 0.0

    def check(self, force=False):
        now = time.monotonic()
        if not force and now - self.last_check < 5: return
        self.last_check = now
        size = sum(p.stat().st_size for p in self.directory.iterdir() if p.is_file())
        if size > self.max_bytes or shutil.disk_usage(self.directory).free < self.reserve_bytes:
            raise CatalogBuildError("build disk budget/free-space reserve exhausted; partial retained")

    def sql_progress(self):
        try: self.check()
        except CatalogBuildError: return 1
        return 0


DDL = """
CREATE TABLE raw_artist(id INTEGER PRIMARY KEY, mbid TEXT NOT NULL, name TEXT NOT NULL);
CREATE TABLE raw_credit(credit INTEGER NOT NULL, position INTEGER NOT NULL, artist INTEGER NOT NULL,
 name TEXT NOT NULL, PRIMARY KEY(credit,position)) WITHOUT ROWID;
CREATE TABLE raw_recording(id INTEGER PRIMARY KEY, mbid TEXT NOT NULL, title TEXT NOT NULL,
 credit INTEGER NOT NULL, duration INTEGER);
CREATE TABLE raw_isrc(id INTEGER PRIMARY KEY, recording INTEGER NOT NULL, isrc TEXT NOT NULL);
CREATE TABLE credit(credit INTEGER PRIMARY KEY, artists TEXT NOT NULL, artist_mbids TEXT NOT NULL,
 search_artists TEXT NOT NULL);
CREATE TABLE tracks(recording_id INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, recording_mbid TEXT NOT NULL,
 title TEXT NOT NULL, normalized_title TEXT NOT NULL, artists TEXT NOT NULL, artist_mbids TEXT NOT NULL,
 duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms>0), version TEXT,
 canonical_rank INTEGER NOT NULL, search_artists TEXT NOT NULL);
CREATE VIRTUAL TABLE track_fts USING fts5(normalized_title, search_artists, version,
 content='tracks', content_rowid='recording_id', tokenize='unicode61 remove_diacritics 2');
CREATE TABLE build_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
"""


def _row(table, row, counters):
    """Domain-invalid rows are accounted for, never silently misattributed."""
    if table == "artist":
        if row["id"] <= 0 or not row["name"] or not row["name"].strip(): return None
        try: mbid = str(uuid.UUID(row["gid"]))
        except (ValueError, TypeError, AttributeError): return None
        return row["id"], mbid, row["name"].strip()
    if table == "artist_credit_name":
        # Retain invalid members too: the entire credit is later excluded,
        # never a partially stripped attribution. Required integer NULLs are
        # schema-invalid and are rejected by the COPY parser upstream.
        return row["artist_credit"], row["position"], row["artist"], (row["name"] or "").strip()
    if table == "recording":
        if row["id"] <= 0 or row["artist_credit"] <= 0 or not row["name"] or not row["name"].strip(): return None
        try: mbid = str(uuid.UUID(row["gid"]))
        except (ValueError, TypeError, AttributeError): return None
        duration = row["length"]
        if duration is not None and duration <= 0:
            counters["nonpositive_duration_to_null"] = counters.get("nonpositive_duration_to_null", 0) + 1
            duration = None
        return row["id"], mbid, row["name"].strip(), row["artist_credit"], duration
    if row["id"] <= 0 or row["recording"] <= 0 or not row["isrc"]: return None
    cleaned = row["isrc"].strip().upper()
    return (row["id"], row["recording"], cleaned) if ISRC.fullmatch(cleaned) else None


def ingest_archive(connection, archive: Path, directory: Path, budget: DiskBudget):
    counts, seen, metadata = {}, set(), {}
    sql = {"artist": "INSERT INTO raw_artist VALUES(?,?,?)",
           "artist_credit_name": "INSERT INTO raw_credit VALUES(?,?,?,?)",
           "recording": "INSERT INTO raw_recording VALUES(?,?,?,?,?)",
           "isrc": "INSERT INTO raw_isrc VALUES(?,?,?)"}
    with tarfile.open(archive, "r|bz2") as bundle:
        for member in bundle:
            name = member.name
            if not name or name.startswith("/") or ".." in name.split("/") or member.issym() or member.islnk():
                raise CatalogBuildError("unsafe archive member")
            if name.startswith("./"): name = name[2:]
            event("TAR_MEMBER", name=name, decompressed_bytes=member.size)
            if name in ("SCHEMA_SEQUENCE", "TIMESTAMP", "REPLICATION_SEQUENCE") and member.isfile():
                if member.size > 128: raise CatalogBuildError("oversized archive metadata")
                metadata[name] = bundle.extractfile(member).read().decode("ascii").strip()
            if not name.startswith("mbdump/"): continue
            table = name[len("mbdump/"):]
            if table not in TABLES: continue
            if metadata.get("SCHEMA_SEQUENCE") != "31":
                raise CatalogBuildError("unsupported/missing MusicBrainz schema sequence (expected validated 31)")
            if table in seen or not member.isfile(): raise CatalogBuildError("duplicate/non-file required table")
            seen.add(table)
            if member.size > budget.max_bytes: raise CatalogBuildError("selected table exceeds declared byte budget")
            stats = {"rows_seen": 0, "rows_inserted": 0, "domain_invalid": 0, "decompressed_bytes": member.size}
            counts[table] = stats
            stream = bundle.extractfile(member)
            batch, batch_bytes, next_report = [], 0, time.monotonic() + 30
            while raw := stream.readline(MAX_LINE + 1):
                if len(raw) > MAX_LINE: raise CatalogBuildError("COPY line exceeds 1MiB safety bound")
                if not raw.strip(): continue
                stats["rows_seen"] += 1
                try: parsed = parse_copy_line(raw.decode("utf8"), TABLE_SCHEMAS[table])
                except UnicodeDecodeError: raise CatalogBuildError("invalid COPY UTF8") from None
                if parsed is None: raise CatalogBuildError(f"invalid positional COPY row in {table}")
                values = _row(table, parsed, stats)
                if values is None: stats["domain_invalid"] += 1
                else:
                    batch.append(values); batch_bytes += len(raw)
                if len(batch) >= BATCH_ROWS or batch_bytes >= BATCH_BYTES:
                    connection.executemany(sql[table], batch); connection.commit()
                    stats["rows_inserted"] += len(batch); batch, batch_bytes = [], 0
                    budget.check()
                if time.monotonic() >= next_report:
                    event("INGEST", table=table, **stats); next_report = time.monotonic() + 30
                    atomic_json(directory / "progress.json", {"state": "INGESTING", "tables": counts, "current_table": table})
            if batch:
                connection.executemany(sql[table], batch); connection.commit(); stats["rows_inserted"] += len(batch)
            if stats["rows_inserted"] == 0 and table != "isrc": raise CatalogBuildError(f"empty required table {table}")
            event("TABLE_COMPLETE", table=table, **stats)
    if seen != set(TABLES): raise CatalogBuildError("missing required COPY tables")
    return counts, metadata


def normalize_catalog(connection, budget):
    event("NORMALIZE_CREDITS")
    cursor = connection.execute("SELECT c.credit,c.position,c.name,a.mbid FROM raw_credit c LEFT JOIN raw_artist a ON a.id=c.artist ORDER BY c.credit,c.position")
    credits, invalid, batch, batch_bytes = 0, 0, [], 0
    for credit_id, members in itertools.groupby(cursor, lambda row: row[0]):
        names, mbids, valid = [], [], True
        for _, position, name, mbid in members:
            if position != len(names) or mbid is None or not name: valid = False
            names.append(name); mbids.append(mbid)
            if len(names) > MAX_CREDIT_MEMBERS: raise CatalogBuildError("single artist credit exceeds safety bound")
        if not valid: invalid += 1; continue
        values = (credit_id, json.dumps(names, ensure_ascii=False), json.dumps(mbids), normalize_text(" ".join(names)))
        batch.append(values); batch_bytes += sum(len(value.encode("utf8")) for value in values[1:])
        if len(batch) >= BATCH_ROWS or batch_bytes >= BATCH_BYTES:
            connection.executemany("INSERT INTO credit VALUES(?,?,?,?)", batch); connection.commit(); credits += len(batch); batch=[]; batch_bytes=0; budget.check()
            if credits % 100000 == 0: event("NORMALIZE_CREDITS", credits=credits, invalid=invalid)
    if batch: connection.executemany("INSERT INTO credit VALUES(?,?,?,?)", batch); connection.commit(); credits += len(batch)
    event("NORMALIZE_TRACKS", credits=credits, invalid_credits=invalid)
    cursor = connection.execute("SELECT r.id,r.mbid,r.title,r.duration,c.artists,c.artist_mbids,c.search_artists FROM raw_recording r JOIN credit c ON c.credit=r.credit ORDER BY r.id")
    tracks, batch, batch_bytes = 0, [], 0
    for recording_id, mbid, title, duration, artists, artist_mbids, search_artists in cursor:
        lowered = normalize_text(title)
        # Same conservative title-derived variant intent as existing search model.
        version = next((token for token in VARIANT_TOKENS if re.search(r"\b" + token + r"\b", lowered)), None)
        batch.append((recording_id, str(track_id_for_recording(mbid)), mbid, title, lowered, artists,
                      artist_mbids, duration, version, 1 if version else 0, search_artists))
        batch_bytes += sum(len(value.encode("utf8")) for value in batch[-1] if isinstance(value,str))
        if len(batch) >= BATCH_ROWS or batch_bytes >= BATCH_BYTES:
            connection.executemany("INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?,?)", batch); connection.commit(); tracks += len(batch); batch=[]; batch_bytes=0; budget.check()
            if tracks % 100000 == 0: event("NORMALIZE_TRACKS", tracks=tracks)
    if batch: connection.executemany("INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?,?)", batch); connection.commit(); tracks += len(batch)
    raw_count = connection.execute("SELECT count(*) FROM raw_recording").fetchone()[0]
    dropped = raw_count - tracks
    if not tracks: raise CatalogBuildError("no valid normalized tracks; refusing an empty full catalog")
    return {"credits": credits, "invalid_credits": invalid, "tracks": tracks, "unresolved_recordings": dropped}


def build_fts(connection, budget):
    event("INDEX_FTS5")
    last_id, indexed = 0, 0
    while True:
        rows, batch_bytes = [], 0
        cursor = connection.execute("SELECT recording_id,normalized_title,search_artists,version FROM tracks WHERE recording_id>? ORDER BY recording_id LIMIT ?", (last_id, BATCH_ROWS))
        for row in cursor:
            rows.append(row); batch_bytes += sum(len(value.encode("utf8")) for value in row if isinstance(value,str))
            if batch_bytes >= BATCH_BYTES: break
        cursor.close()
        if not rows: break
        connection.executemany("INSERT INTO track_fts(rowid,normalized_title,search_artists,version) VALUES(?,?,?,?)", rows)
        connection.commit(); last_id = rows[-1][0]; indexed += len(rows); budget.check()
        if indexed % 100000 == 0: event("INDEX_FTS5", indexed=indexed)
    # External-content rank=1 checks index entries against content, not only FTS structure.
    event("VALIDATE_FTS5", indexed=indexed)
    connection.execute("INSERT INTO track_fts(track_fts,rank) VALUES('integrity-check',1)"); connection.commit()
    if connection.execute("PRAGMA quick_check").fetchone()[0] != "ok": raise CatalogBuildError("SQLite integrity failure")
    return indexed


def build_catalog(archive: Path, snapshot: Snapshot, directory: Path, *, source_commit: str,
                  max_build_gib=100, reserve_gib=15):
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit): raise CatalogBuildError("exact source commit required")
    if type(max_build_gib) is not int or type(reserve_gib) is not int or min(max_build_gib,reserve_gib) <= 0:
        raise CatalogBuildError("positive disk budgets required")
    if directory.exists(): raise CatalogBuildError("version directory already exists; partial retained, choose a new build revision")
    parent = directory.parent.resolve()
    if not parent.is_dir() or directory.resolve() == Path(directory.resolve().anchor): raise CatalogBuildError("dedicated existing build root required")
    if archive.stat().st_size != snapshot.archive_bytes or digest(archive) != snapshot.sha256:
        raise CatalogBuildError("archive length/hash mismatch")
    if shutil.disk_usage(parent).free < (max_build_gib + reserve_gib) * GIB:
        raise CatalogBuildError("insufficient space for explicit build budget + free reserve")
    with job_lock(parent):
        directory.mkdir()
        budget = DiskBudget(directory, max_build_gib * GIB, reserve_gib * GIB)
        partial = directory / "catalog.sqlite3.partial"
        connection = sqlite3.connect(partial)
        # Fixed memory/disk strategy; transactional batches, no giant WAL/VACUUM.
        connection.execute("PRAGMA page_size=4096")
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA cache_size=-65536")
        connection.execute("PRAGMA temp_store=FILE")
        connection.execute(f"PRAGMA max_page_count={max_build_gib * GIB // 4096}")
        connection.set_progress_handler(budget.sql_progress, 100000)
        report = {"schema": "REMANENCE-FULL-CATALOG-V1", "state": "BUILDING", "source_commit": source_commit,
                  "snapshot": snapshot.__dict__, "artist_sampling": False, "index": "SQLITE_FTS5",
                  "release_year_artwork": "NOT_IMPORTED", "schema_sequence": "31", "disk_budget_gib": max_build_gib, "free_reserve_gib": reserve_gib}
        atomic_json(directory / "manifest.json", report)
        try:
            connection.executescript(DDL)
            tables, archive_metadata = ingest_archive(connection, archive, directory, budget)
            report.update(tables=tables, archive_metadata=archive_metadata)
            atomic_json(directory / "progress.json", {"state": "NORMALIZING", "tables": tables})
            report["normalization"] = normalize_catalog(connection, budget)
            atomic_json(directory / "progress.json", {"state": "INDEXING", "normalization": report["normalization"]})
            report["indexed"] = build_fts(connection, budget)
            if report["indexed"] != report["normalization"]["tracks"]: raise CatalogBuildError("index coverage mismatch")
            connection.execute("INSERT INTO build_metadata VALUES('state','READY')")
            connection.execute("INSERT INTO build_metadata VALUES('snapshot',?)", (snapshot.revision,)); connection.commit()
            connection.close()
            partial.replace(directory / "catalog.sqlite3")
            report.update(state="READY", completed_utc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
            atomic_json(directory / "manifest.json", report)
            atomic_json(directory / "progress.json", {"state": "READY", "tracks": report["indexed"]})
            event("READY", directory=str(directory), tracks=report["indexed"])
            return report
        except BaseException as error:
            connection.close(); report.update(state="FAILED", error_class=type(error).__name__, error=str(error)[:512])
            atomic_json(directory / "manifest.json", report); event("FAILED", **{"error": str(error)[:512]})
            raise


def iter_documents(database: Path):
    """Bounded portable projection into the existing SearchDocument contract."""
    connection = sqlite3.connect(f"file:{database.resolve()}?mode=ro", uri=True)
    try:
        if connection.execute("SELECT value FROM build_metadata WHERE key='state'").fetchone() != ("READY",):
            raise CatalogBuildError("catalog is not validated READY")
        for own_id, mbid, title, artists, duration, version, canonical in connection.execute("SELECT id,recording_mbid,title,artists,duration_ms,version,canonical_rank FROM tracks ORDER BY recording_id"):
            yield build_search_document(MusicTrack.create(track_id=uuid.UUID(own_id), recording_mbid=mbid,
                title=title, artists=json.loads(artists), duration_ms=duration, version=version, is_canonical=canonical==0))
    finally: connection.close()


def publish_revision(root: Path, revision: Path, *, expected_current: str | None):
    """Explicit atomic pointer swap in this isolated artifact root; retains old."""
    root, revision = root.resolve(), revision.resolve()
    if revision.parent != root: raise CatalogBuildError("revision must be inside the dedicated root")
    manifest = json.loads((revision / "manifest.json").read_text())
    if manifest.get("state") != "READY" or not (revision / "catalog.sqlite3").is_file(): raise CatalogBuildError("refusing unvalidated revision")
    connection = sqlite3.connect(f"file:{revision / 'catalog.sqlite3'}?mode=ro", uri=True)
    try:
        state = connection.execute("SELECT value FROM build_metadata WHERE key='state'").fetchone()
        snapshot = connection.execute("SELECT value FROM build_metadata WHERE key='snapshot'").fetchone()
        if state != ("READY",) or snapshot != (manifest["snapshot"]["revision"],):
            raise CatalogBuildError("manifest/database validation state mismatch")
    finally: connection.close()
    with job_lock(root):
        pointer = root / "current.json"
        current = json.loads(pointer.read_text()) if pointer.exists() else None
        if (current["revision"] if current else None) != expected_current: raise CatalogBuildError("active revision changed; refusing stale publish")
        if current: atomic_json(root / "previous.json", current)
        atomic_json(pointer, {"revision": revision.name, "snapshot": manifest["snapshot"]["revision"]})
