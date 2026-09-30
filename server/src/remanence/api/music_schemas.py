"""Public music search response schemas (ARCHITECTURE-v1 sections 26-27).

The client sees own track data: id (own RemanenceTrackId), title, artists,
version, release, year, durationMs, artworkAvailable — plus exactly one
provider identifier, ``recordingMbid`` (T062).

``recordingMbid`` is the canonical MusicBrainz *recording* id the catalog
already stores. It is metadata only: it lets the sender client resolve and
fetch cover art itself. No artwork URL, artwork digest, artwork byte, release
id, release-group id or any other provider identifier is ever exposed here, and
the backend never fetches, proxies, caches or stores artwork (V5 invariant I1).
"""

from __future__ import annotations

import uuid

from pydantic import BaseModel, ConfigDict, field_validator

from remanence.music.domain import parse_optional_recording_mbid


class MusicSearchResultItem(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    id: uuid.UUID
    title: str
    artists: list[str]
    version: str | None = None
    release: str | None = None
    year: int | None = None
    durationMs: int | None = None
    artworkAvailable: bool = False
    recordingMbid: str | None = None

    @field_validator("recordingMbid", mode="after")
    @classmethod
    def _canonical_recording_mbid(cls, value: str | None) -> str | None:
        try:
            return parse_optional_recording_mbid(value)
        except ValueError:
            raise ValueError("invalid recordingMbid") from None

    @field_validator("title")
    @classmethod
    def _non_empty_title(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("invalid title")
        return value

    @field_validator("artists")
    @classmethod
    def _non_empty_artists(cls, value: list[str]) -> list[str]:
        if not value or any(type(a) is not str or not a.strip() for a in value):
            raise ValueError("invalid artists")
        return value


class MusicSearchResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    results: list[MusicSearchResultItem]
    total: int
    offset: int

    @field_validator("total", "offset")
    @classmethod
    def _non_negative_page(cls, value: int) -> int:
        if value < 0:
            raise ValueError("invalid page")
        return value
