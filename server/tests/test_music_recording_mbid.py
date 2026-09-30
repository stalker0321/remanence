"""Recording-MBID metadata plumbing tests (T062).

The backend exposes exactly one provider identifier — the canonical MusicBrainz
*recording* MBID — so the sender client can resolve and fetch cover art itself.
These tests pin the identifier's shape, its path through domain → index document
→ search adapter → API schema, and the hard negative: no artwork URL, digest,
mime, dimension, release or release-group id may appear anywhere on that path.

No network, no Meilisearch, no database: the adapter is exercised through its
pure response parser and the schema through the pydantic model directly.
"""

from __future__ import annotations

import uuid

import pytest

from remanence.api.music_schemas import MusicSearchResultItem
from remanence.music.domain import (
    MusicTrack,
    TrackSearchResult,
    parse_optional_recording_mbid,
)
from remanence.music.search.document import (
    DOCUMENT_FIELDS,
    SEARCH_ATTRIBUTES_TO_RETRIEVE,
    build_search_document,
)
from remanence.music.search.meilisearch import MeilisearchMusicSearch
from remanence.music.ports import MusicSearchError

_MBID = "46fe768c-7b38-4147-9f02-815b9f0759e2"
_OWN = uuid.uuid5(uuid.NAMESPACE_DNS, "recording-mbid-plumbing")


def test_parse_optional_recording_mbid_accepts_canonical_and_none() -> None:
    assert parse_optional_recording_mbid(None) is None
    assert parse_optional_recording_mbid(_MBID) == _MBID


@pytest.mark.parametrize(
    "value",
    [
        "46FE768C-7B38-4147-9F02-815B9F0759E2",  # uppercase spelling
        "46fe768c7b3841479f02815b9f0759e2",  # no dashes
        "46fe768c-7b38-4147-9f02-815b9f0759e",  # short
        "not-a-uuid",
        "",
        " ",
    ],
)
def test_parse_optional_recording_mbid_rejects_malformed(value: object) -> None:
    with pytest.raises(ValueError):
        parse_optional_recording_mbid(value)


def test_music_track_create_validates_and_stores_recording_mbid() -> None:
    track = MusicTrack.create(
        title="Bohemian Rhapsody",
        artists=("Queen",),
        recording_mbid=_MBID,
    )
    assert track.recording_mbid == _MBID
    for bad in ("BOHEMIAN", "46fe768c-7b38-4147-9f02-815b9f0759e2 "):
        with pytest.raises(ValueError):
            MusicTrack.create(title="x", artists=("y",), recording_mbid=bad)
    # Absent stays absent — never derived, never guessed.
    assert MusicTrack.create(title="x", artists=("y",)).recording_mbid is None


def test_hit_and_public_dict_carry_only_the_recording_mbid() -> None:
    track = MusicTrack.create(
        title="Bohemian Rhapsody",
        artists=("Queen",),
        recording_mbid=_MBID,
    )
    hit = TrackSearchResult.from_track(track)
    assert hit.recording_mbid == _MBID

    public = hit.public_dict()
    assert public["recordingMbid"] == _MBID
    assert set(public) == {
        "id",
        "title",
        "artists",
        "version",
        "release",
        "year",
        "durationMs",
        "artworkAvailable",
        "recordingMbid",
    }
    # Artwork addressable by the client, but never addressed FOR it here.
    for banned in ("url", "sha256", "mimeType", "width", "height", "provider"):
        assert banned not in public


def test_search_document_and_retrieval_allow_list_carry_recording_mbid() -> None:
    track = MusicTrack.create(title="x", artists=("y",), recording_mbid=_MBID)
    doc = build_search_document(track)
    assert doc["recordingMbid"] == _MBID
    assert set(doc) == set(DOCUMENT_FIELDS)
    assert "recordingMbid" in DOCUMENT_FIELDS
    assert "recordingMbid" in SEARCH_ATTRIBUTES_TO_RETRIEVE
    # The allow-list still forbids every artwork-shaped field.
    for banned in ("artworkUrl", "url", "sha256", "releaseMbid", "releaseGroupMbid"):
        assert banned not in DOCUMENT_FIELDS
        assert banned not in SEARCH_ATTRIBUTES_TO_RETRIEVE
    # A track without a stored id indexes an explicit null, not a placeholder.
    assert build_search_document(MusicTrack.create(title="x", artists=("y",)))[
        "recordingMbid"
    ] is None


def test_meilisearch_response_parser_carries_recording_mbid() -> None:
    payload = {
        "hits": [
            {
                "id": str(_OWN),
                "title": "Bohemian Rhapsody",
                "artists": ["Queen"],
                "recordingMbid": _MBID,
            },
            {"id": str(_OWN), "title": "Other", "artists": ["Queen"]},
        ]
    }
    hits = MeilisearchMusicSearch.parse_search_response(payload, limit=10)
    assert [h.recording_mbid for h in hits] == [_MBID, None]


def test_meilisearch_response_parser_fails_closed_on_malformed_mbid() -> None:
    payload = {
        "hits": [
            {
                "id": str(_OWN),
                "title": "x",
                "artists": ["y"],
                "recordingMbid": "not-a-canonical-uuid",
            }
        ]
    }
    with pytest.raises(MusicSearchError):
        MeilisearchMusicSearch.parse_search_response(payload, limit=10)


def test_api_schema_accepts_canonical_and_rejects_malformed_mbid() -> None:
    ok = MusicSearchResultItem(
        id=_OWN, title="t", artists=["a"], recordingMbid=_MBID
    )
    assert ok.recordingMbid == _MBID
    absent = MusicSearchResultItem(id=_OWN, title="t", artists=["a"])
    assert absent.recordingMbid is None
    with pytest.raises(ValueError):
        MusicSearchResultItem(
            id=_OWN, title="t", artists=["a"], recordingMbid="not-a-uuid"
        )
    # extra="forbid": an artwork descriptor can never be smuggled onto the DTO.
    with pytest.raises(ValueError):
        MusicSearchResultItem(
            id=_OWN,
            title="t",
            artists=["a"],
            recordingMbid=_MBID,
            artworkUrl="https://coverartarchive.org/x",
        )
