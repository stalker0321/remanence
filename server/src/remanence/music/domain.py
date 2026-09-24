"""Provider-neutral music domain (ARCHITECTURE-v1 sections 2, 7, 8, 42).

Identity rule (section 8): ``RemanenceTrackId`` is a UUID owned by
Remanence. External identifiers (MusicBrainz MBID, ISRC, Spotify ID, ...)
are stored separately in :class:`ExternalIdentifier` and must never become
the domain ``id``. This structural split is what lets a catalog source be
replaced without changing capsules, UX, or existing track IDs.
"""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass

# Canonical UUID text, e.g. "be30e36b-...". Mirrors the strict canonical-UUID
# convention used by the capsule HTTP layer (reject non-canonical spellings).
_CANONICAL_UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
_WHITESPACE_RE = re.compile(r"\s+")

# Generic external-identity namespaces (section 7). Open-ended on purpose:
# new providers add a namespace string, never a new domain column.
EXTERNAL_NAMESPACE_MUSICBRAINZ_RECORDING = "musicbrainz_recording"
EXTERNAL_NAMESPACE_ISRC = "isrc"
EXTERNAL_NAMESPACE_SPOTIFY = "spotify"
EXTERNAL_NAMESPACE_APPLE_MUSIC = "apple_music"
EXTERNAL_NAMESPACE_DEEZER = "deezer"


def normalize_text(value: str) -> str:
    """Lowercase/strip/collapse whitespace for search normalization."""
    if type(value) is not str:
        raise TypeError("normalize_text requires str")
    return _WHITESPACE_RE.sub(" ", value.strip().lower())


def parse_remanence_track_id(value: object) -> uuid.UUID:
    """Parse and validate an own ``RemanenceTrackId`` (UUID, canonical text)."""
    if isinstance(value, uuid.UUID):
        parsed = value
    elif isinstance(value, str):
        try:
            parsed = uuid.UUID(value)
        except (ValueError, AttributeError, TypeError):
            raise ValueError("invalid RemanenceTrackId") from None
    else:
        raise ValueError("invalid RemanenceTrackId")
    if str(parsed) != (value if isinstance(value, str) else str(parsed)):
        raise ValueError("invalid RemanenceTrackId")
    if _CANONICAL_UUID_RE.fullmatch(str(parsed)) is None:
        raise ValueError("invalid RemanenceTrackId")
    return parsed


@dataclass(frozen=True, slots=True)
class MusicArtist:
    id: uuid.UUID
    name: str
    normalized_name: str

    @classmethod
    def create(cls, name: str, *, artist_id: uuid.UUID | None = None) -> "MusicArtist":
        if type(name) is not str or not name.strip():
            raise ValueError("artist name must be a non-empty string")
        return cls(
            id=artist_id or uuid.uuid4(),
            name=name.strip(),
            normalized_name=normalize_text(name),
        )


@dataclass(frozen=True, slots=True)
class ExternalIdentifier:
    """One external identity attached to an own track (section 7)."""

    track_id: uuid.UUID
    namespace: str
    external_id: str
    source: str
    confidence: str = "exact"
    active: bool = True

    def __post_init__(self) -> None:
        # D10: the own track reference must itself be a valid RemanenceTrackId.
        # A provider identifier smuggled into track_id would corrupt identity
        # matching, so fail closed here rather than at query time. Only a
        # real UUID instance is accepted — not even a canonical UUID string.
        if not isinstance(self.track_id, uuid.UUID):
            raise ValueError("track_id must be a UUID RemanenceTrackId")
        parse_remanence_track_id(self.track_id)
        if type(self.namespace) is not str or not self.namespace.strip():
            raise ValueError("namespace must be a non-empty string")
        if type(self.external_id) is not str or not self.external_id.strip():
            raise ValueError("external_id must be a non-empty string")
        if type(self.source) is not str or not self.source.strip():
            raise ValueError("source must be a non-empty string")


@dataclass(frozen=True, slots=True)
class MusicTrack:
    """Own catalog track. ``id`` is always a Remanence UUID, never an MBID."""

    id: uuid.UUID
    title: str
    normalized_title: str
    artists: tuple[str, ...]
    release: str | None = None
    year: int | None = None
    duration_ms: int | None = None
    version: str | None = None
    is_canonical: bool = True
    has_artwork: bool = False

    @classmethod
    def create(
        cls,
        *,
        title: str,
        artists: tuple[str, ...] | list[str],
        track_id: uuid.UUID | None = None,
        release: str | None = None,
        year: int | None = None,
        duration_ms: int | None = None,
        version: str | None = None,
        is_canonical: bool = True,
        has_artwork: bool = False,
    ) -> "MusicTrack":
        if type(title) is not str or not title.strip():
            raise ValueError("title must be a non-empty string")
        names = tuple(a.strip() for a in artists)
        if not names or any(type(a) is not str or not a for a in names):
            raise ValueError("artists must be a non-empty list of names")
        if year is not None and (type(year) is not int or not 1000 <= year <= 9999):
            raise ValueError("year must be a four-digit int")
        if duration_ms is not None and (
            type(duration_ms) is not int or duration_ms <= 0
        ):
            raise ValueError("duration_ms must be a positive int")
        if version is not None and (type(version) is not str or not version.strip()):
            raise ValueError("version must be a non-empty string or None")
        clean_title = title.strip()
        return cls(
            id=parse_remanence_track_id(track_id) if track_id is not None else uuid.uuid4(),
            title=clean_title,
            normalized_title=normalize_text(clean_title),
            artists=names,
            release=release.strip() if isinstance(release, str) and release.strip() else None,
            year=year,
            duration_ms=duration_ms,
            version=version.strip() if isinstance(version, str) else None,
            is_canonical=bool(is_canonical),
            has_artwork=bool(has_artwork),
        )


@dataclass(frozen=True, slots=True)
class TrackSearchResult:
    """Public search hit (section 27). No provider metadata by design."""

    id: uuid.UUID
    title: str
    artists: tuple[str, ...]
    version: str | None = None
    release: str | None = None
    year: int | None = None
    duration_ms: int | None = None
    artwork_available: bool = False

    @classmethod
    def from_track(cls, track: MusicTrack) -> "TrackSearchResult":
        if not isinstance(track, MusicTrack):
            raise TypeError("from_track requires MusicTrack")
        return cls(
            id=parse_remanence_track_id(track.id),
            title=track.title,
            artists=track.artists,
            version=track.version,
            release=track.release,
            year=track.year,
            duration_ms=track.duration_ms,
            artwork_available=bool(track.has_artwork),
        )

    def public_dict(self) -> dict:
        """Allow-listed API shape; never includes MBID/Spotify/provider fields."""
        return {
            "id": str(self.id),
            "title": self.title,
            "artists": list(self.artists),
            "version": self.version,
            "release": self.release,
            "year": self.year,
            "durationMs": self.duration_ms,
            "artworkAvailable": bool(self.artwork_available),
        }
