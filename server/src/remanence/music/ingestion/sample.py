"""Bounded sample runner: tar -> records + explicit coverage metadata.

Five streaming tar passes keep memory bounded by the sample, never by
dump size (each pass is a single sequential scan; nothing is extracted):

1. ``artist`` -> hash-ranked top-N artist rows (heap-bounded, O(N)).
2. ``artist_credit_name`` (member artist allowlisted only) -> candidate
   credit ids. Retention-free: ids only, rows dropped.
3. ``artist_credit_name`` (credit candidate only) -> FULL member rows, so
   selection sees every member and partial credits are never split.
4. ``recording`` (credit selected only) -> kept recording ids.
5. ``isrc`` (recording kept only) -> attached ISRCs.

Pass 3 exists because pass 2 sees a filtered view: a credit whose
non-allowlisted members were dropped would look complete when it is
not. The candidate + refetch pair keeps the no-split invariant exact
without retaining the full credit table.

``SampleCoverage.to_dict()`` is the explicit coverage record the
1000-artist real sample must log: method, passes, tables/rows
seen-kept-malformed, drop reasons, caps in force, limits hit, and the
schema note. No Postgres, no search index, no product response is
produced here by design.
"""

from __future__ import annotations

import heapq
import itertools
from dataclasses import dataclass
from pathlib import Path

from remanence.music.ingestion import identity as _identity
from remanence.music.ingestion.limits import (
    CANDIDATE_ID_BYTES,
    MAX_CANDIDATE_BYTES,
    MAX_CANDIDATE_CREDITS,
    MAX_MALFORMED_ROWS_PER_TABLE,
    MAX_MEMBER_BYTES,
    MAX_RETAINED_BYTES_PER_TABLE,
    MAX_RETAINED_ROWS_PER_TABLE,
    MAX_ROWS_PER_TABLE,
    MAX_SAMPLE_ARTISTS,
    SAMPLE_TABLES,
)
from remanence.music.ingestion.mbdump import (
    SCHEMA_NOTE,
    ImporterError,
    ImporterLimitError,
    TableParseStats,
    parse_tables,
)
from remanence.music.ingestion.normalize import (
    SAMPLE_METHOD,
    NormalizeStats,
    artist_sample_key,
    build_allowlist,
    normalize_tables,
)

# Provenance: exact streaming pass count. Bump only with run_sample.
SAMPLE_PASSES = 5


@dataclass(frozen=True, slots=True)
class SampleCoverage:
    """Explicit, loggable record of what the sample run covered."""

    tar_name: str
    artist_limit: int
    sample_method: str
    passes: int
    schema_note: str
    tables: tuple[TableParseStats, ...]
    members_seen: int
    members_skipped_unknown: int
    artists_seen: int
    artists_kept: int
    recordings_seen: int
    recordings_kept: int
    dropped_unresolvable_credit: int
    dropped_outside_allowlist: int
    dropped_empty_title: int
    dropped_bad_mbid: int
    negative_length_sanitized: int
    isrcs_seen: int
    isrcs_kept: int
    isrcs_invalid: int
    isrcs_orphan: int
    duplicate_mbid_collapses: int
    isrc_collisions: int
    limits_hit: tuple[str, ...]
    row_cap: int
    member_byte_cap: int
    malformed_cap: int
    retained_cap: int
    retained_byte_cap: int
    candidate_cap: int
    candidate_byte_cap: int

    def to_dict(self) -> dict[str, object]:
        return {
            "tar_name": self.tar_name,
            "artist_limit": self.artist_limit,
            "sample_method": self.sample_method,
            "passes": self.passes,
            "schema_note": self.schema_note,
            "tables": [
                {
                    "table": t.table,
                    "rows_seen": t.rows_seen,
                    "rows_kept": t.rows_kept,
                    "rows_malformed": t.rows_malformed,
                    "bytes_read": t.bytes_read,
                }
                for t in self.tables
            ],
            "members_seen": self.members_seen,
            "members_skipped_unknown": self.members_skipped_unknown,
            "artists_seen": self.artists_seen,
            "artists_kept": self.artists_kept,
            "recordings_seen": self.recordings_seen,
            "recordings_kept": self.recordings_kept,
            "dropped_unresolvable_credit": self.dropped_unresolvable_credit,
            "dropped_outside_allowlist": self.dropped_outside_allowlist,
            "dropped_empty_title": self.dropped_empty_title,
            "dropped_bad_mbid": self.dropped_bad_mbid,
            "negative_length_sanitized": self.negative_length_sanitized,
            "isrcs_seen": self.isrcs_seen,
            "isrcs_kept": self.isrcs_kept,
            "isrcs_invalid": self.isrcs_invalid,
            "isrcs_orphan": self.isrcs_orphan,
            "duplicate_mbid_collapses": self.duplicate_mbid_collapses,
            "isrc_collisions": self.isrc_collisions,
            "limits_hit": list(self.limits_hit),
            "caps": {
                "row_cap": self.row_cap,
                "member_byte_cap": self.member_byte_cap,
                "malformed_cap": self.malformed_cap,
                "retained_cap": self.retained_cap,
                "retained_byte_cap": self.retained_byte_cap,
                "candidate_cap": self.candidate_cap,
                "candidate_byte_cap": self.candidate_byte_cap,
            },
        }


@dataclass(frozen=True, slots=True)
class SampleResult:
    imports: tuple[_identity.TrackImport, ...]
    coverage: SampleCoverage


class _HashTopN:
    """Stateful keep-predicate holding only the hash-smallest N rows.

    Max-heap on rank via negated ints (ranks are hex digests): heap[0]
    always holds the currently-largest kept rank, so replacement keeps
    exactly the N smallest. Returns False always — retention lives here,
    never in the parser.
    """

    def __init__(self, limit: int) -> None:
        if type(limit) is not int or limit <= 0:
            raise ValueError("limit must be a positive int")
        self._limit = limit
        self._heap: list[tuple[int, int, dict[str, object]]] = []
        self._sequence = itertools.count()

    def __call__(self, row: dict[str, object]) -> bool:
        keyed = artist_sample_key(row)
        if keyed is None:
            return False
        rank, _row = keyed
        negated = -int(rank, 16)
        entry = (negated, next(self._sequence), row)
        if len(self._heap) < self._limit:
            heapq.heappush(self._heap, entry)
        elif negated > self._heap[0][0]:
            heapq.heapreplace(self._heap, entry)
        return False

    def rows(self) -> list[dict[str, object]]:
        return [row for _, _, row in sorted(self._heap, reverse=True)]


class _CreditCandidates:
    """Collect credit ids touching the allowlist; retains no rows.

    B2: the set itself is capped (count + estimated bytes). Breaching
    either aborts fail-closed — an unbounded candidate set would rebuy
    the memory blowup streaming was designed to avoid.
    """

    def __init__(
        self,
        allowlist: frozenset[int],
        count_cap: int = MAX_CANDIDATE_CREDITS,
        byte_cap: int = MAX_CANDIDATE_BYTES,
    ) -> None:
        if type(count_cap) is not int or count_cap <= 0:
            raise ImporterError("candidate count cap must be a positive int")
        if type(byte_cap) is not int or byte_cap <= 0:
            raise ImporterError("candidate byte cap must be a positive int")
        self._allowlist = allowlist
        self._count_cap = count_cap
        self._byte_cap = byte_cap
        self.candidates: set[int] = set()

    def __call__(self, row: dict[str, object]) -> bool:
        credit_id = row.get("artist_credit")
        if type(credit_id) is int and row.get("artist") in self._allowlist:
            if credit_id not in self.candidates:
                if len(self.candidates) + 1 > self._count_cap:
                    raise ImporterLimitError("candidate credit count cap exceeded")
                if (len(self.candidates) + 1) * CANDIDATE_ID_BYTES > self._byte_cap:
                    raise ImporterLimitError("candidate credit byte cap exceeded")
                self.candidates.add(credit_id)
        return False


def run_sample(
    tar_path: str | Path,
    *,
    artist_limit: int = MAX_SAMPLE_ARTISTS,
    row_cap: int = MAX_ROWS_PER_TABLE,
    member_byte_cap: int = MAX_MEMBER_BYTES,
    malformed_cap: int = MAX_MALFORMED_ROWS_PER_TABLE,
    retained_cap: int = MAX_RETAINED_ROWS_PER_TABLE,
    retained_byte_cap: int = MAX_RETAINED_BYTES_PER_TABLE,
    candidate_cap: int = MAX_CANDIDATE_CREDITS,
    candidate_byte_cap: int = MAX_CANDIDATE_BYTES,
) -> SampleResult:
    """Run the bounded sample. Raises ImporterError/ImporterLimitError.

    B1: ``member_byte_cap`` is the configurable override. The default
    4 GiB guard stays; raise it ONLY to a value measured by
    ``probe_member_sizes`` on the target archive (guarded raise: the
    bound remains a positive int and is still enforced while streaming).
    """
    if type(artist_limit) is not int or artist_limit <= 0:
        raise ValueError("artist_limit must be a positive int")
    path = Path(tar_path)
    if not path.is_file():
        raise ImporterError(f"tar not found: {path.name}")
    caps = {
        "row_cap": row_cap,
        "member_byte_cap": member_byte_cap,
        "malformed_cap": malformed_cap,
        "retained_cap": retained_cap,
        "retained_byte_cap": retained_byte_cap,
    }

    # Pass 1: hash-smallest artist rows only (O(artist_limit) memory).
    topn = _HashTopN(artist_limit)
    _, report_artist = parse_tables(
        path, ("artist",), keep_by_table={"artist": topn}, **caps
    )
    artist_rows = topn.rows()
    artist_stat_in = report_artist.tables[0]
    artist_stat = TableParseStats(
        table="artist",
        rows_seen=artist_stat_in.rows_seen,
        rows_kept=len(artist_rows),
        rows_malformed=artist_stat_in.rows_malformed,
        bytes_read=artist_stat_in.bytes_read,
    )

    # Pass 2: candidate credit ids (no row retention).
    allowlist, _ = build_allowlist(artist_rows, [], artist_limit=artist_limit)
    candidates = _CreditCandidates(
        allowlist, count_cap=candidate_cap, byte_cap=candidate_byte_cap
    )
    _, report2a = parse_tables(
        path,
        ("artist_credit_name",),
        keep_by_table={"artist_credit_name": candidates},
        **caps,
    )

    # Pass 3: full member rows for candidate credits -> exact selection.
    pass2, report2b = parse_tables(
        path,
        ("artist_credit_name",),
        keep_by_table={
            "artist_credit_name": lambda row: row.get("artist_credit")
            in candidates.candidates
        },
        **caps,
    )
    _, selected = build_allowlist(
        artist_rows, pass2["artist_credit_name"], artist_limit=artist_limit
    )

    # Pass 4: recordings whose credit was selected.
    pass3, report3 = parse_tables(
        path,
        ("recording",),
        keep_by_table={
            "recording": lambda row: row.get("artist_credit") in selected
        },
        **caps,
    )
    kept_recording_ids = frozenset(
        row["id"] for row in pass3["recording"] if type(row.get("id")) is int
    )

    # Pass 5: ISRCs for kept recordings only.
    pass4, report4 = parse_tables(
        path,
        ("isrc",),
        keep_by_table={"isrc": lambda row: row.get("recording") in kept_recording_ids},
        **caps,
    )

    tables: dict[str, list[dict[str, object]]] = {
        "artist": artist_rows,
        "artist_credit_name": pass2["artist_credit_name"],
        "recording": pass3["recording"],
        "isrc": pass4["isrc"],
    }
    missing = [t for t in SAMPLE_TABLES if t not in tables]
    if missing:
        raise ImporterError(f"required tables missing: {missing}")

    records, stats = normalize_tables(tables, artist_limit=artist_limit)
    imports, duplicates = _identity.assign_track_ids(records)

    seen_isrcs: set[str] = set()
    collisions = 0
    for record in records:
        for isrc in record.isrcs:
            if isrc in seen_isrcs:
                collisions += 1
            else:
                seen_isrcs.add(isrc)

    reports = (report_artist, report2a, report2b, report3, report4)
    stats_by_table: dict[str, TableParseStats] = {}
    for report in reports:
        for stat in report.tables:
            # Pass 2a retains nothing; the pass-3 (report2b) entry is the
            # authoritative artist_credit_name stat. Later wins.
            stats_by_table[stat.table] = stat
    stats_by_table["artist"] = artist_stat
    coverage = SampleCoverage(
        tar_name=path.name,
        artist_limit=artist_limit,
        sample_method=SAMPLE_METHOD,
        passes=SAMPLE_PASSES,
        schema_note=SCHEMA_NOTE,
        tables=tuple(stats_by_table[t] for t in SAMPLE_TABLES if t in stats_by_table),
        members_seen=sum(r.members_seen for r in reports),
        members_skipped_unknown=sum(r.members_skipped_unknown for r in reports),
        artists_seen=artist_stat.rows_seen,
        artists_kept=stats.artists_kept,
        recordings_seen=stats.recordings_seen,
        recordings_kept=stats.recordings_kept,
        dropped_unresolvable_credit=stats.dropped_unresolvable_credit,
        dropped_outside_allowlist=stats.dropped_outside_allowlist,
        dropped_empty_title=stats.dropped_empty_title,
        dropped_bad_mbid=stats.dropped_bad_mbid,
        negative_length_sanitized=stats.negative_length_sanitized,
        isrcs_seen=stats.isrcs_seen,
        isrcs_kept=stats.isrcs_kept,
        isrcs_invalid=stats.isrcs_invalid,
        isrcs_orphan=stats.isrcs_orphan,
        duplicate_mbid_collapses=duplicates,
        isrc_collisions=collisions,
        limits_hit=_limits_hit(
            stats_by_table, stats, artist_stat.rows_seen, artist_limit
        ),
        row_cap=row_cap,
        member_byte_cap=member_byte_cap,
        malformed_cap=malformed_cap,
        retained_cap=retained_cap,
        retained_byte_cap=retained_byte_cap,
        candidate_cap=candidate_cap,
        candidate_byte_cap=candidate_byte_cap,
    )
    return SampleResult(imports=imports, coverage=coverage)


def _limits_hit(
    table_stats: dict[str, TableParseStats],
    stats: NormalizeStats,
    artists_scanned: int,
    artist_limit: int,
) -> tuple[str, ...]:
    """Record caps that actually constrained or flagged this run.

    The truncation flag fires only when the artist cap bound the run
    (kept == limit with more scanned), never for a small-but-complete set.
    """
    hits: list[str] = []
    for table, stat in table_stats.items():
        if stat.rows_malformed > 0:
            hits.append(f"{table}:malformed_rows={stat.rows_malformed}")
    if stats.artists_kept == artist_limit and artists_scanned > stats.artists_kept:
        hits.append(
            f"artist_truncated=scanned:{artists_scanned},kept:{stats.artists_kept}"
        )
    return tuple(hits)
