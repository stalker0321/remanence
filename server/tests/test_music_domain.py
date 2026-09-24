"""Music domain identity tests (ARCHITECTURE-v1 sections 2, 7, 8)."""

import uuid

import pytest

from remanence.music.domain import (
    ExternalIdentifier,
    MusicTrack,
    TrackSearchResult,
    normalize_text,
    parse_remanence_track_id,
)
from remanence.music.search.document import (
    CANONICAL_RANK_DEFAULT,
    CANONICAL_RANK_VARIANT,
    DOCUMENT_FIELDS,
    build_search_document,
    canonical_rank,
    meilisearch_index_settings,
    parse_search_document_id,
)


def test_remanence_track_id_is_canonical_uuid() -> None:
    track_id = uuid.uuid4()
    assert parse_remanence_track_id(track_id) == track_id
    assert parse_remanence_track_id(str(track_id)) == track_id
    with pytest.raises(ValueError):
        parse_remanence_track_id(str(track_id).upper())
    with pytest.raises(ValueError):
        parse_remanence_track_id("0c63-not-a-uuid")
    with pytest.raises(ValueError):
        parse_remanence_track_id(123)


def test_external_id_never_becomes_domain_id() -> None:
    own = uuid.uuid4()
    mbid = "0c63f8a1-1234-5678-9abc-def012345678"
    external = ExternalIdentifier(
        track_id=own,
        namespace="musicbrainz_recording",
        external_id=mbid,
        source="musicbrainz",
    )
    assert external.track_id == own
    assert external.external_id == mbid
    # An MBID in the domain-id slot must be rejected structurally: it is not
    # even a valid RemanenceTrackId unless it happens to parse as UUID, and
    # the document parser only accepts the own id field.
    track = MusicTrack.create(title="505", artists=("Arctic Monkeys",), track_id=own)
    assert track.id == own
    assert track.id != external.external_id or isinstance(track.id, uuid.UUID)
    doc = build_search_document(track)
    assert doc["id"] == str(own)
    assert parse_search_document_id(doc) == own
    with pytest.raises(ValueError):
        parse_search_document_id({"id": mbid + "-not-canonical"})
    with pytest.raises(ValueError):
        parse_search_document_id({"id": "spotify:4uLU6hMCjMI75M1A2tKUQ"})


def test_normalize_and_document_shape_match_architecture() -> None:
    assert normalize_text("  Arctic   MONKEYS ") == "arctic monkeys"
    track = MusicTrack.create(
        title="505",
        artists=("Arctic Monkeys",),
        release="Favourite Worst Nightmare",
        year=2007,
        duration_ms=253000,
        is_canonical=True,
        has_artwork=True,
    )
    doc = build_search_document(track)
    assert set(doc) == set(DOCUMENT_FIELDS)
    assert doc["title"] == "505"
    assert doc["normalizedTitle"] == "505"
    assert doc["artists"] == ["Arctic Monkeys"]
    assert doc["normalizedArtists"] == ["arctic monkeys"]
    assert doc["isCanonical"] is True
    assert doc["canonicalRank"] == CANONICAL_RANK_DEFAULT == 0
    assert doc["hasArtwork"] is True
    result = TrackSearchResult.from_track(track)
    public = result.public_dict()
    assert set(public) == {
        "id",
        "title",
        "artists",
        "version",
        "release",
        "year",
        "durationMs",
        "artworkAvailable",
    }
    assert "musicBrainzId" not in public
    assert "spotifyId" not in public
    assert "mbid" not in str(public).lower()


def test_index_settings_are_intent_oriented() -> None:
    settings = meilisearch_index_settings()
    assert "title" in settings["searchableAttributes"]
    assert "artists" in settings["searchableAttributes"]
    assert "isCanonical" in settings["filterableAttributes"]
    # R1 regression: canonical-first is a NUMERIC sort. The boolean field
    # must never return to sortableAttributes (Meilisearch cannot sort on it).
    assert "canonicalRank" in settings["sortableAttributes"]
    assert "isCanonical" not in settings["sortableAttributes"]


def test_canonical_rank_is_numeric_and_derived_from_is_canonical() -> None:
    studio = MusicTrack.create(title="505", artists=("Arctic Monkeys",), is_canonical=True)
    live = MusicTrack.create(
        title="505", artists=("Arctic Monkeys",), version="Live", is_canonical=False
    )
    assert canonical_rank(studio) == 0
    assert canonical_rank(live) == 1
    assert type(canonical_rank(studio)) is int
    assert build_search_document(studio)["canonicalRank"] == CANONICAL_RANK_DEFAULT
    assert build_search_document(live)["canonicalRank"] == CANONICAL_RANK_VARIANT
    with pytest.raises(TypeError):
        canonical_rank("not-a-track")


def test_external_identifier_rejects_non_uuid_track_id() -> None:
    own = uuid.uuid4()
    with pytest.raises(ValueError):
        ExternalIdentifier(
            track_id="be30e36b-1111-4111-8111-000000000001",
            namespace="musicbrainz_recording",
            external_id="0c63f8a1-1234-5678-9abc-def012345678",
            source="musicbrainz",
        )
    with pytest.raises(ValueError):
        ExternalIdentifier(
            track_id="spotify:4uLU6hMCjMI75M1A2tKUQ",
            namespace="spotify",
            external_id="4uLU6hMCjMI75M1A2tKUQ",
            source="import",
        )
    assert ExternalIdentifier(
        track_id=own,
        namespace="isrc",
        external_id="GBAYE0700505",
        source="musicbrainz",
    ).track_id == own
