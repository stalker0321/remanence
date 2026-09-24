"""Normalization: parsed mbdump rows -> provider-neutral catalog records.

Join chain for the sample stage (ARCH sections 6-7):

```text
recording.artist_credit -> artist_credit_name -> artist (names)
isrc.recording         -> recording           (ISRC external IDs)
```

Only recordings whose credit resolves entirely inside the deterministic
artist sample are kept. Sampling is uniform-hash over the artist id
keyspace (see ``build_allowlist``) — it is deterministic and unbiased by
id order, but it is NOT claimed representative of catalog distribution.

Release/year via track -> medium -> release is deferred to the next slice,
so records carry no release or year yet — explicitly, not by omission.
"""

from __future__ import annotations

import hashlib
import re
import uuid
from dataclasses import dataclass

from remanence.music.domain import normalize_text
from remanence.music.ingestion.limits import MAX_SAMPLE_ARTISTS

_ISRC_RE = re.compile(r"^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

# Artist sampling method, recorded verbatim in the sample coverage.
# Uniform over the id keyspace via SHA-256 rank; deterministic across runs.
SAMPLE_METHOD = "hash-sha256-ascending"


def _artist_rank(artist_id: int) -> str:
    return hashlib.sha256(f"artist:{artist_id}".encode("ascii")).hexdigest()


def _valid_artists(
    artist_rows: list[dict[str, object]],
) -> dict[int, tuple[str, str]]:
    """Validated id -> (gid, name), the exact set sampling may choose from."""
    artists: dict[int, tuple[str, str]] = {}
    for row in artist_rows:
        artist_id = row.get("id")
        gid = _valid_mbid(row.get("gid"))
        name = row.get("name")
        if type(artist_id) is not int or gid is None:
            continue
        if type(name) is not str or not name.strip():
            continue
        assert isinstance(artist_id, int)
        artists[artist_id] = (gid, name.strip())
    return artists


def artist_sample_key(row: dict[str, object]) -> tuple[str, dict[str, object]] | None:
    """Rank key for streaming top-N artist selection, or None if invalid.

    Same validity rules as :func:`_valid_artists` (int id, parseable gid,
    non-blank name) so stream-time filtering and batch selection agree.
    """
    if type(row.get("id")) is not int:
        return None
    artist_id = row.get("id")
    assert isinstance(artist_id, int)
    if _valid_mbid(row.get("gid")) is None:
        return None
    name = row.get("name")
    if type(name) is not str or not name.strip():
        return None
    return (_artist_rank(artist_id), row)


def build_allowlist(
    artist_rows: list[dict[str, object]],
    credit_rows: list[dict[str, object]],
    *,
    artist_limit: int = MAX_SAMPLE_ARTISTS,
) -> tuple[frozenset[int], frozenset[int]]:
    """Select artists by hash rank; return (allowlist, selected_credits).

    Rank key is SHA-256 over ``artist:<id>``: deterministic across runs
    and uniform over the id keyspace, unbiased by id order. It is NOT
    claimed representative of catalog distribution — see SAMPLE_METHOD.

    A credit is selected only when EVERY member artist is allowlisted —
    partial credits are never split, so no recording is attributed to a
    truncated artist list.
    """
    if type(artist_limit) is not int or artist_limit <= 0:
        raise ValueError("artist_limit must be a positive int")
    artists = _valid_artists(artist_rows)
    ranked = sorted(artists, key=_artist_rank)
    allowlist = frozenset(ranked[:artist_limit])

    members: dict[int, set[int]] = {}
    for row in credit_rows:
        credit_id = row.get("artist_credit")
        artist_id = row.get("artist")
        if type(credit_id) is int and type(artist_id) is int:
            members.setdefault(credit_id, set()).add(artist_id)
    selected = frozenset(
        credit_id
        for credit_id, artist_ids in members.items()
        if artist_ids and artist_ids <= set(allowlist)
    )
    return allowlist, selected


@dataclass(frozen=True, slots=True)
class NormalizedRecord:
    """One provider-neutral catalog record, pre-identity (ARCH section 6)."""

    title: str
    normalized_title: str
    artists: tuple[str, ...]
    normalized_artists: tuple[str, ...]
    duration_ms: int | None
    recording_mbid: str
    artist_mbids: tuple[str, ...]
    isrcs: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class NormalizeStats:
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


@dataclass
class _Counter:
    artists_seen: int = 0
    artists_kept: int = 0
    recordings_seen: int = 0
    recordings_kept: int = 0
    dropped_unresolvable_credit: int = 0
    dropped_outside_allowlist: int = 0
    dropped_empty_title: int = 0
    dropped_bad_mbid: int = 0
    negative_length_sanitized: int = 0
    isrcs_seen: int = 0
    isrcs_kept: int = 0
    isrcs_invalid: int = 0
    isrcs_orphan: int = 0


def _valid_mbid(value: object) -> str | None:
    if type(value) is not str or not value:
        return None
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError, TypeError):
        return None
    return str(parsed).lower()


def _valid_isrc(value: object) -> str | None:
    if type(value) is not str or not value:
        return None
    cleaned = value.strip().upper()
    if _ISRC_RE.fullmatch(cleaned) is None:
        return None
    return cleaned


def normalize_tables(
    tables: dict[str, list[dict[str, object]]],
    *,
    artist_limit: int = MAX_SAMPLE_ARTISTS,
) -> tuple[tuple[NormalizedRecord, ...], NormalizeStats]:
    """Join + normalize parsed tables into catalog records (deterministic)."""
    if type(artist_limit) is not int or artist_limit <= 0:
        raise ValueError("artist_limit must be a positive int")
    counter = _Counter()

    artists = _valid_artists(tables.get("artist", []))
    counter.artists_seen = len(tables.get("artist", []))

    allowlist, _selected = build_allowlist(
        tables.get("artist", []),
        tables.get("artist_credit_name", []),
        artist_limit=artist_limit,
    )
    counter.artists_kept = len(allowlist)

    # Credit members in official position order (deterministic artist order).
    credit_members: dict[int, list[tuple[int, int, str, str]]] = {}
    for row in tables.get("artist_credit_name", []):
        credit_id = row.get("artist_credit")
        position = row.get("position")
        artist_id = row.get("artist")
        name = row.get("name")
        join_phrase = row.get("join_phrase")
        if type(credit_id) is not int or type(artist_id) is not int:
            continue
        if type(position) is not int or position < 0:
            continue
        if type(name) is not str or not name.strip():
            continue
        phrase = join_phrase if type(join_phrase) is str else ""
        credit_members.setdefault(credit_id, []).append(
            (position, artist_id, name.strip(), phrase)
        )
    for members in credit_members.values():
        members.sort(key=lambda member: member[0])

    isrc_by_recording: dict[int, list[str]] = {}
    for row in tables.get("isrc", []):
        counter.isrcs_seen += 1
        recording_id = row.get("recording")
        isrc = _valid_isrc(row.get("isrc"))
        if type(recording_id) is not int:
            counter.isrcs_invalid += 1
            continue
        if isrc is None:
            counter.isrcs_invalid += 1
            continue
        isrc_by_recording.setdefault(recording_id, []).append(isrc)

    records: list[NormalizedRecord] = []
    kept_recording_ids: set[int] = set()
    for row in tables.get("recording", []):
        counter.recordings_seen += 1
        recording_id = row.get("id")
        mbid = _valid_mbid(row.get("gid"))
        title = row.get("name")
        credit_id = row.get("artist_credit")
        length = row.get("length")
        if mbid is None:
            counter.dropped_bad_mbid += 1
            continue
        if type(title) is not str or not title.strip():
            counter.dropped_empty_title += 1
            continue
        if type(credit_id) is not int:
            counter.dropped_unresolvable_credit += 1
            continue
        members = credit_members.get(credit_id)
        if not members:
            counter.dropped_unresolvable_credit += 1
            continue
        if any(member_id not in allowlist for _, member_id, _, _ in members):
            counter.dropped_outside_allowlist += 1
            continue
        names: list[str] = []
        mbids: list[str] = []
        broken = False
        for _, member_id, display_name, _phrase in members:
            entry = artists.get(member_id)
            if entry is None:
                broken = True
                break
            names.append(display_name or entry[1])
            mbids.append(entry[0])
        if broken or not names:
            counter.dropped_unresolvable_credit += 1
            continue
        duration_ms: int | None = None
        if length is not None:
            if type(length) is not int:
                counter.dropped_bad_mbid += 1
                continue
            if length < 0:
                counter.negative_length_sanitized += 1
            else:
                duration_ms = length
        isrcs: list[str] = []
        if type(recording_id) is int:
            for isrc in isrc_by_recording.get(recording_id, []):
                if isrc not in isrcs:
                    isrcs.append(isrc)
                    counter.isrcs_kept += 1
        clean_title = title.strip()
        records.append(
            NormalizedRecord(
                title=clean_title,
                normalized_title=normalize_text(clean_title),
                artists=tuple(names),
                normalized_artists=tuple(normalize_text(n) for n in names),
                duration_ms=duration_ms,
                recording_mbid=mbid,
                artist_mbids=tuple(mbids),
                isrcs=tuple(isrcs),
            )
        )
        counter.recordings_kept += 1
        if type(recording_id) is int:
            kept_recording_ids.add(recording_id)

    # ISRC rows whose recording never survived normalization are orphans:
    # counted for coverage, never attached to a wrong track.
    orphans = sum(
        len(values)
        for recording_id, values in isrc_by_recording.items()
        if recording_id not in kept_recording_ids
    )
    stats = NormalizeStats(
        artists_seen=counter.artists_seen,
        artists_kept=counter.artists_kept,
        recordings_seen=counter.recordings_seen,
        recordings_kept=counter.recordings_kept,
        dropped_unresolvable_credit=counter.dropped_unresolvable_credit,
        dropped_outside_allowlist=counter.dropped_outside_allowlist,
        dropped_empty_title=counter.dropped_empty_title,
        dropped_bad_mbid=counter.dropped_bad_mbid,
        negative_length_sanitized=counter.negative_length_sanitized,
        isrcs_seen=counter.isrcs_seen,
        isrcs_kept=counter.isrcs_kept,
        isrcs_invalid=counter.isrcs_invalid,
        isrcs_orphan=orphans,
    )
    return tuple(records), stats
