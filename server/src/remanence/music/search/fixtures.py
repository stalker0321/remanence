"""Deterministic fixture catalog for tests (NOT a user-facing catalog).

The fixtures exist only so ranking and API-contract tests run without a
live Meilisearch instance, a production database, or the 7GiB MusicBrainz
dump. Production ingestion (dump -> staging revision -> index rebuild) is a
later slice and is intentionally not implemented here.
"""

from __future__ import annotations

import uuid

from remanence.music.domain import MusicTrack

_FIXTURE_IDS = {
    "studio": uuid.UUID("be30e36b-1111-4111-8111-000000000001"),
    "live": uuid.UUID("be30e36b-2222-4222-8222-000000000002"),
    "remix": uuid.UUID("be30e36b-3333-4333-8333-000000000003"),
    "cover": uuid.UUID("be30e36b-4444-4444-8444-000000000004"),
    "other": uuid.UUID("be30e36b-5555-4555-8555-000000000005"),
}


def build_fixture_tracks() -> tuple[MusicTrack, ...]:
    studio = MusicTrack.create(
        track_id=_FIXTURE_IDS["studio"],
        title="505",
        artists=("Arctic Monkeys",),
        release="Favourite Worst Nightmare",
        year=2007,
        duration_ms=253000,
        version=None,
        is_canonical=True,
        has_artwork=True,
    )
    live = MusicTrack.create(
        track_id=_FIXTURE_IDS["live"],
        title="505",
        artists=("Arctic Monkeys",),
        release="Live at the Apollo",
        year=2008,
        duration_ms=261000,
        version="Live at the Apollo",
        is_canonical=False,
        has_artwork=True,
    )
    remix = MusicTrack.create(
        track_id=_FIXTURE_IDS["remix"],
        title="505",
        artists=("Arctic Monkeys",),
        release="505 Remixes",
        year=2009,
        duration_ms=240000,
        version="Remix",
        is_canonical=False,
        has_artwork=False,
    )
    cover = MusicTrack.create(
        track_id=_FIXTURE_IDS["cover"],
        title="505",
        artists=("Cover Band",),
        release="Tribute Nights",
        year=2015,
        duration_ms=250000,
        version="Cover",
        is_canonical=False,
        has_artwork=False,
    )
    other = MusicTrack.create(
        track_id=_FIXTURE_IDS["other"],
        title="Do I Wanna Know?",
        artists=("Arctic Monkeys",),
        release="AM",
        year=2013,
        duration_ms=272000,
        version=None,
        is_canonical=True,
        has_artwork=True,
    )
    return (studio, live, remix, cover, other)


FIXTURE_TRACKS: tuple[MusicTrack, ...] = build_fixture_tracks()
