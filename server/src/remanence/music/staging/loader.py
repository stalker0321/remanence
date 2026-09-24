"""Idempotent JSONL -> music staging loader (code-only, no live DB here).

Reads the bounded sample JSONL (postflight SHA a84d67bb…), validates every
line at the trust boundary, and merges rows into the ARCH section 7
staging tables. Reloading the same file converges to identical state:
tracks/artists merge by primary key, external identities by their
composite key, credit membership by (track, artist, position).

Fail-closed rules:
- One invalid line never poisons a batch: it is counted with a reason and
  skipped; valid lines still load.
- A database error rolls back the in-flight batch and aborts the load.
- Duration NULL means unknown. Zero/negative durations are invalid rows,
  never stored (CHECK constraint backs this at the DB level too).
- ISRCs shared by several tracks are stored on every track (unmerged);
  the collision count is reported, never resolved.
- Duplicate track_id lines (same MBID twice): first line wins, repeats
  counted. File order is deterministic (track_id-sorted JSONL). The same
  first-wins rule applies to artist display names: the first-seen spelling
  stays, later case variants still resolve to the same row.

Artist identity: rows carry ``artist_mbids`` parallel to ``artists``
(sample JSONL v2). Artist rows are keyed by
``UUIDv5(namespace, "musicbrainz:artist:<mbid>")`` — deterministic,
homonym-safe, own-UUID like track identity. Display names are attributes
only and never participate in identity. Rows WITHOUT ``artist_mbids``
(old v1 sample shape) are explicitly rejected, never name-merged: a v1
file cannot safely encode artist identity, so the loader refuses it
instead of inventing identity (STOP condition, enforced).
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy.orm import Session

from remanence.music.domain import (
    EXTERNAL_NAMESPACE_ISRC,
    EXTERNAL_NAMESPACE_MUSICBRAINZ_RECORDING,
    ExternalIdentifier,
    MusicTrack,
    normalize_text,
    parse_remanence_track_id,
)
from remanence.music.staging.models import (
    StagedArtist,
    StagedExternalId,
    StagedTrack,
    StagedTrackArtist,
    TrackStatus,
)

# Stable versioned namespace for MBID-derived staging artist identity.
# Changing it re-keys every artist: hardcoded constant, never generated.
ARTIST_MBID_NAMESPACE_V1 = uuid.UUID("f5ea8c37-b76f-4c53-a972-97dbf5bf5a71")
_ARTIST_MBID_KEY_PREFIX = "musicbrainz:artist:"

_ISRC_RE = re.compile(r"^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

# Trust-boundary budgets for the JSONL file itself.
MAX_LOAD_ROWS = 1_000_000
MAX_LINE_BYTES = 1024 * 1024
DEFAULT_BATCH_ROWS = 1000

# JSONL contract (postflight sample). duration_ms is optional for forward
# compatibility: absent or null today, validated identically if present.
_KNOWN_KEYS = frozenset(
    {
        "track_id", "title", "artists", "artist_mbids",
        "recording_mbid", "isrcs", "duration_ms",
    }
)


class LoaderError(RuntimeError):
    """Staging load aborted (I/O, database, or budget failure)."""


class RowInvalid(ValueError):
    """One JSONL line failed trust-boundary validation (counted, skipped)."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True, slots=True)
class ValidatedRow:
    track_id: uuid.UUID
    title: str
    normalized_title: str
    artists: tuple[str, ...]
    artist_mbids: tuple[str, ...]
    recording_mbid: str
    isrcs: tuple[str, ...]
    duration_ms: int | None


@dataclass(slots=True)
class LoadStats:
    lines_seen: int = 0
    rows_valid: int = 0
    rows_invalid: int = 0
    invalid_reasons: dict[str, int] = field(default_factory=dict)
    tracks_merged: int = 0
    track_repeats: int = 0
    artists_seen: int = 0
    external_ids_merged: int = 0
    isrc_collision_pairs: int = 0
    batches_committed: int = 0


def artist_id_for_mbid(artist_mbid: str) -> uuid.UUID:
    """Deterministic staging artist id from an authoritative artist MBID."""
    if type(artist_mbid) is not str or not artist_mbid:
        raise ValueError("artist_mbid must be a non-empty string")
    try:
        normalized = str(uuid.UUID(artist_mbid)).lower()
    except (ValueError, AttributeError, TypeError):
        raise ValueError("artist_mbid must be a UUID string") from None
    return uuid.uuid5(ARTIST_MBID_NAMESPACE_V1, f"{_ARTIST_MBID_KEY_PREFIX}{normalized}")


def _valid_mbid(value: object) -> str:
    if type(value) is not str or not value:
        raise RowInvalid("bad_recording_mbid")
    try:
        return str(uuid.UUID(value)).lower()
    except (ValueError, AttributeError, TypeError):
        raise RowInvalid("bad_recording_mbid") from None


def _valid_isrc(value: object) -> str:
    if type(value) is not str or not value:
        raise RowInvalid("bad_isrc")
    cleaned = value.strip().upper()
    if _ISRC_RE.fullmatch(cleaned) is None:
        raise RowInvalid("bad_isrc")
    return cleaned


def validate_row(payload: object) -> ValidatedRow:
    """Validate one decoded JSONL line; raises RowInvalid with a reason."""
    if type(payload) is not dict:
        raise RowInvalid("not_an_object")
    unknown = set(payload) - _KNOWN_KEYS
    if unknown:
        raise RowInvalid("unknown_keys")
    for key in ("track_id", "title", "recording_mbid"):
        if key not in payload:
            raise RowInvalid("missing_keys")
    try:
        track_id = parse_remanence_track_id(payload["track_id"])
    except ValueError:
        raise RowInvalid("bad_track_id") from None
    artists = payload.get("artists")
    if type(artists) is not list or not artists:
        raise RowInvalid("bad_artists")
    if any(type(name) is not str or not name.strip() for name in artists):
        raise RowInvalid("bad_artists")
    if type(payload.get("title")) is not str:
        raise RowInvalid("bad_track_fields")
    if "artist_mbids" not in payload:
        # Old v1 sample shape: no authoritative artist identity exists.
        # Refuse instead of merging by display name (STOP condition).
        raise RowInvalid("missing_artist_mbids")
    artist_mbids = payload.get("artist_mbids")
    if type(artist_mbids) is not list or len(artist_mbids) != len(artists):
        raise RowInvalid("artist_mbid_mismatch")
    duration = payload.get("duration_ms")
    if duration is not None and (type(duration) is not int or duration <= 0):
        raise RowInvalid("bad_duration")
    try:
        track = MusicTrack.create(
            title=payload["title"],
            artists=[name.strip() for name in artists],
            track_id=track_id,
            duration_ms=duration,
        )
    except (ValueError, TypeError):
        raise RowInvalid("bad_track_fields") from None
    recording_mbid = _valid_mbid(payload["recording_mbid"])
    try:
        artist_mbid_list = tuple(str(uuid.UUID(value)).lower() for value in artist_mbids)
    except (ValueError, AttributeError, TypeError):
        raise RowInvalid("bad_artist_mbid") from None
    isrcs_raw = payload.get("isrcs", [])
    if type(isrcs_raw) is not list:
        raise RowInvalid("bad_isrcs")
    isrcs: list[str] = []
    for code in isrcs_raw:
        cleaned = _valid_isrc(code)
        if cleaned not in isrcs:
            isrcs.append(cleaned)
    return ValidatedRow(
        track_id=track.id,
        title=track.title,
        normalized_title=track.normalized_title,
        artists=track.artists,
        artist_mbids=artist_mbid_list,
        recording_mbid=recording_mbid,
        isrcs=tuple(isrcs),
        duration_ms=track.duration_ms,
    )


def _merge_row(
    session: Session,
    row: ValidatedRow,
    stats: LoadStats,
    seen_artist_ids: set[uuid.UUID],
) -> None:
    session.merge(
        StagedTrack(
            id=row.track_id,
            title=row.title,
            normalized_title=row.normalized_title,
            duration_ms=row.duration_ms,
            variant=None,
            first_release_year=None,
            status=TrackStatus.ACTIVE,
        )
    )
    stats.tracks_merged += 1
    for position, (name, artist_mbid) in enumerate(zip(row.artists, row.artist_mbids)):
        artist_id = artist_id_for_mbid(artist_mbid)
        if artist_id not in seen_artist_ids:
            seen_artist_ids.add(artist_id)
            session.merge(
                StagedArtist(
                    id=artist_id,
                    name=name,
                    normalized_name=normalize_text(name),
                )
            )
        session.merge(
            StagedTrackArtist(
                track_id=row.track_id, artist_id=artist_id, position=position
            )
        )
    external_rows = [
        ExternalIdentifier(
            track_id=row.track_id,
            namespace=EXTERNAL_NAMESPACE_MUSICBRAINZ_RECORDING,
            external_id=row.recording_mbid,
            source="musicbrainz",
            confidence="exact",
            active=True,
        )
    ]
    external_rows.extend(
        ExternalIdentifier(
            track_id=row.track_id,
            namespace=EXTERNAL_NAMESPACE_ISRC,
            external_id=code,
            source="musicbrainz",
            confidence="exact",
            active=True,
        )
        for code in row.isrcs
    )
    for external in external_rows:
        session.merge(
            StagedExternalId(
                track_id=external.track_id,
                namespace=external.namespace,
                external_id=external.external_id,
                source=external.source,
                confidence=external.confidence,
                active=external.active,
            )
        )
        stats.external_ids_merged += 1


def load_staged_jsonl(
    session: Session,
    path: str | Path,
    *,
    batch_rows: int = DEFAULT_BATCH_ROWS,
) -> LoadStats:
    """Stream a validated sample JSONL into staging tables, idempotently.

    Commits per batch; any database error rolls back the in-flight batch
    and aborts with LoaderError. Rerunning the same file is a no-op.
    """
    if type(batch_rows) is not int or batch_rows <= 0:
        raise LoaderError("batch_rows must be a positive int")
    file_path = Path(path)
    stats = LoadStats()
    seen_tracks: set[uuid.UUID] = set()
    seen_artists: set[str] = set()
    seen_artist_ids: set[uuid.UUID] = set()
    isrc_tracks: dict[str, set[uuid.UUID]] = {}
    pending = 0
    try:
        handle = file_path.open("r", encoding="utf-8")
    except OSError as exc:
        raise LoaderError(f"cannot open JSONL: {file_path.name}") from exc
    with handle:
        for raw_line in handle:
            if len(raw_line.encode("utf-8")) > MAX_LINE_BYTES:
                raise LoaderError("line over byte budget")
            stats.lines_seen += 1
            if stats.lines_seen > MAX_LOAD_ROWS:
                raise LoaderError("row budget exceeded")
            if not raw_line.strip():
                stats.rows_invalid += 1
                stats.invalid_reasons["blank_line"] = (
                    stats.invalid_reasons.get("blank_line", 0) + 1
                )
                continue
            try:
                payload = json.loads(raw_line)
            except json.JSONDecodeError:
                stats.rows_invalid += 1
                stats.invalid_reasons["bad_json"] = (
                    stats.invalid_reasons.get("bad_json", 0) + 1
                )
                continue
            try:
                row = validate_row(payload)
            except RowInvalid as exc:
                stats.rows_invalid += 1
                stats.invalid_reasons[exc.reason] = (
                    stats.invalid_reasons.get(exc.reason, 0) + 1
                )
                continue
            stats.rows_valid += 1
            if row.track_id in seen_tracks:
                stats.track_repeats += 1
                continue
            seen_tracks.add(row.track_id)
            for code in row.isrcs:
                isrc_tracks.setdefault(code, set()).add(row.track_id)
            try:
                _merge_row(session, row, stats, seen_artist_ids)
                seen_artists.update(row.artist_mbids)
                pending += 1
            except Exception as exc:
                session.rollback()
                raise LoaderError(
                    f"batch aborted at line {stats.lines_seen}: {type(exc).__name__}"
                ) from exc
            if pending >= batch_rows:
                try:
                    session.commit()
                except Exception as exc:
                    session.rollback()
                    raise LoaderError(
                        f"commit aborted at line {stats.lines_seen}: {type(exc).__name__}"
                    ) from exc
                stats.batches_committed += 1
                pending = 0
    if pending:
        try:
            session.commit()
        except Exception as exc:
            session.rollback()
            raise LoaderError(f"final commit aborted: {type(exc).__name__}") from exc
        stats.batches_committed += 1
    stats.artists_seen = len(seen_artists)
    stats.isrc_collision_pairs = sum(
        len(track_ids) - 1 for track_ids in isrc_tracks.values() if len(track_ids) > 1
    )
    return stats
