"""Deterministic RemanenceTrackId assignment (ARCH sections 8-9).

Primary key for this slice is the MusicBrainz recording MBID:

```text
RemanenceTrackId = UUIDv5(NAMESPACE, "musicbrainz:recording:<mbid>")
```

Properties that matter:

- Deterministic: re-importing the same dump yields the same IDs, so a
  staging revision can be rebuilt and compared without touching capsules.
- Never equal to the MBID itself (UUIDv5 output differs by construction),
  satisfying section 8 structurally.
- Idempotent: duplicate MBID rows collapse to one track ID.

ISRC handling follows section 9 conservatively: ISRCs are attached as
external identifiers, and same-batch ISRC collisions are counted in the
sample coverage for the future TrackIdentityMatcher. No fuzzy cross-record
merge happens in this slice — two similar recordings stay two tracks.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from remanence.music.domain import (
    EXTERNAL_NAMESPACE_ISRC,
    EXTERNAL_NAMESPACE_MUSICBRAINZ_RECORDING,
    ExternalIdentifier,
)
from remanence.music.ingestion.normalize import NormalizedRecord

# Stable versioned namespace. Changing it re-keys every track, so it is a
# hardcoded constant — never generated at runtime.
NAMESPACE_REMANENCE_MUSICBRAINZ_V1 = uuid.UUID("fc2376bc-516b-4c3e-a25e-cff6caaebf39")

_RECORDING_KEY_PREFIX = "musicbrainz:recording:"


@dataclass(frozen=True, slots=True)
class TrackImport:
    """One import-ready track: own ID + normalized record + external IDs."""

    track_id: uuid.UUID
    record: NormalizedRecord
    external_ids: tuple[ExternalIdentifier, ...]


def track_id_for_recording(recording_mbid: str) -> uuid.UUID:
    """Deterministically map a recording MBID to its RemanenceTrackId."""
    if type(recording_mbid) is not str or not recording_mbid:
        raise ValueError("recording_mbid must be a non-empty string")
    try:
        normalized = str(uuid.UUID(recording_mbid)).lower()
    except (ValueError, AttributeError, TypeError):
        raise ValueError("recording_mbid must be a UUID string") from None
    return uuid.uuid5(
        NAMESPACE_REMANENCE_MUSICBRAINZ_V1, f"{_RECORDING_KEY_PREFIX}{normalized}"
    )


def assign_track_ids(
    records: tuple[NormalizedRecord, ...] | list[NormalizedRecord],
) -> tuple[tuple[TrackImport, ...], int]:
    """Assign own IDs. Returns (imports, duplicate_mbid_collapses)."""
    imports: list[TrackImport] = []
    seen: dict[str, uuid.UUID] = {}
    duplicates = 0
    for record in records:
        if not isinstance(record, NormalizedRecord):
            raise TypeError("assign_track_ids requires NormalizedRecord")
        track_id = track_id_for_recording(record.recording_mbid)
        if record.recording_mbid in seen:
            duplicates += 1
            assert seen[record.recording_mbid] == track_id
        else:
            seen[record.recording_mbid] = track_id
        external_ids: list[ExternalIdentifier] = [
            ExternalIdentifier(
                track_id=track_id,
                namespace=EXTERNAL_NAMESPACE_MUSICBRAINZ_RECORDING,
                external_id=record.recording_mbid,
                source="musicbrainz",
                confidence="exact",
                active=True,
            )
        ]
        for isrc in record.isrcs:
            external_ids.append(
                ExternalIdentifier(
                    track_id=track_id,
                    namespace=EXTERNAL_NAMESPACE_ISRC,
                    external_id=isrc,
                    source="musicbrainz",
                    confidence="exact",
                    active=True,
                )
            )
        imports.append(
            TrackImport(
                track_id=track_id, record=record, external_ids=tuple(external_ids)
            )
        )
    return tuple(imports), duplicates
