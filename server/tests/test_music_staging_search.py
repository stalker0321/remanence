"""Staging search tests: intent ranking, pagination, MBID safety.

SQLite-backed via schema_translate_map (music.* -> main.*) with tiny
synthetic fixtures incl. Cyrillic/Latin, homonyms, LIKE metacharacters,
and an ISRC collision. No live Postgres, no Meili, no endpoints.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from remanence.music.domain import normalize_text
from remanence.music.ports import MusicSearchError
from remanence.music.search.staging import PostgresStagingSearch
from remanence.music.staging.loader import artist_id_for_mbid
from remanence.music.staging.models import (
    MusicBase,
    StagedArtist,
    StagedExternalId,
    StagedTrack,
    StagedTrackArtist,
    TrackStatus,
)

_M1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
_M2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
_M3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
_M4 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
_M5 = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
_M6 = "ffffffff-ffff-4fff-8fff-ffffffffffff"
_M7 = "11111111-2222-4333-8444-555555555555"
_M8 = "66666666-7777-4888-8999-aaaaaaaaaaaa"
_AM = "99999999-9999-4999-8999-999999999999"
_KINO = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
_BEY = "12345678-1234-4123-8123-123456789012"
_KATE_A = "22222222-2222-4222-8222-222222222222"
_KATE_B = "33333333-3333-4333-8333-333333333333"
_BAND = "44444444-4444-4444-8444-444444444444"


def _tid(tag: str) -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_DNS, f"staging-search-{tag}")


def _seed(session: Session) -> None:
    artists = [
        ("Arctic Monkeys", _M1),
        ("Cover Band", _M2),
        ("Кино", _KINO),
        ("Beyoncé", _BEY),
        ("Kate", _KATE_A),
        ("Kate", _KATE_B),
        ("Band_Under", _BAND),
    ]
    for name, mbid in artists:
        session.add(
            StagedArtist(
                id=artist_id_for_mbid(mbid),
                name=name,
                normalized_name=normalize_text(name),
            )
        )
    tracks = [
        ("t1-505-studio", "505", None, None),
        ("t2-505-live", "505", "Live", None),
        ("t3-505-cover", "505", None, None),
        ("t4-gruppa", "Группа крови", None, None),
        ("t5-cafe", "Café del Mar", None, None),
        ("t6-song-a", "Song", None, None),
        ("t7-song-b", "Song", None, None),
        ("t8-pct", "100% Pure", None, None),
    ]
    for tag, title, variant, _ in tracks:
        session.add(
            StagedTrack(
                id=_tid(tag),
                title=title,
                normalized_title=normalize_text(title),
                duration_ms=None,
                variant=variant,
                first_release_year=None,
                status=TrackStatus.ACTIVE,
            )
        )
    credits = [
        ("t1-505-studio", _M1, 0),
        ("t2-505-live", _M1, 0),
        ("t3-505-cover", _M2, 0),
        ("t4-gruppa", _KINO, 0),
        ("t5-cafe", _BEY, 0),
        ("t6-song-a", _KATE_A, 0),
        ("t7-song-b", _KATE_B, 0),
        ("t8-pct", _BAND, 0),
    ]
    for tag, mbid, position in credits:
        session.add(
            StagedTrackArtist(
                track_id=_tid(tag),
                artist_id=artist_id_for_mbid(mbid),
                position=position,
            )
        )
    # ISRC collision across t1/t2: stored, never consulted by search.
    for tag in ("t1-505-studio", "t2-505-live"):
        session.add(
            StagedExternalId(
                track_id=_tid(tag),
                namespace="isrc",
                external_id="GBAYE0700505",
                source="musicbrainz",
                confidence="exact",
                active=True,
            )
        )
    session.commit()


def _search() -> tuple[PostgresStagingSearch, Session]:
    engine = create_engine(
        "sqlite://", execution_options={"schema_translate_map": {"music": None}}
    )
    MusicBase.metadata.create_all(engine)
    session = Session(engine)
    _seed(session)
    return PostgresStagingSearch(lambda: Session(engine)), session


def _ids(hits) -> list[str]:
    return [str(hit.id) for hit in hits]


def test_intent_order_exact_first_variant_demoted() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("arctic monkeys 505", 10)
    assert total == 2
    assert _ids(hits) == [str(_tid("t1-505-studio")), str(_tid("t2-505-live"))]
    assert hits[0].title == "505" and hits[0].version is None
    assert hits[1].version == "Live"


def test_bare_title_returns_all_variants_canonical_first() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("505", 10)
    assert total == 3
    # t1/t3 tie with no artist signal: stable track-id order, not luck.
    assert _ids(hits)[:2] == sorted(
        [str(_tid("t1-505-studio")), str(_tid("t3-505-cover"))]
    )
    # The versioned variant sorts last without a variant token.
    assert hits[-1].id == _tid("t2-505-live")
    assert {hit.id for hit in hits} == {
        _tid("t1-505-studio"), _tid("t2-505-live"), _tid("t3-505-cover")
    }


def test_variant_query_surfaces_variant() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("505 live", 10)
    assert total == 1 and hits[0].id == _tid("t2-505-live")


def test_cyrillic_match() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("кино группа крови", 10)
    assert total == 1 and hits[0].title == "Группа крови"
    assert hits[0].artists == ("Кино",)
    hits, total = search.search_with_total("кино", 10)
    assert total == 1


def test_like_metacharacters_literal() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("100% pure", 10)
    assert total == 1 and hits[0].id == _tid("t8-pct")
    hits, total = search.search_with_total("band_under", 10)
    assert total == 1 and hits[0].id == _tid("t8-pct")
    # A bare "%" is literal: it matches only the track containing "%",
    # never everything (no match-all wildcard).
    hits, total = search.search_with_total("%", 10)
    assert total == 1 and _ids(hits) == [str(_tid("t8-pct"))]


def test_homonyms_both_returned_with_own_artists() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("song kate", 10)
    assert total == 2
    by_id = {hit.id: hit for hit in hits}
    assert by_id[_tid("t6-song-a")].artists == ("Kate",)
    assert by_id[_tid("t7-song-b")].artists == ("Kate",)
    # Deterministic tiebreak by track id, not insertion luck.
    assert _ids(hits) == sorted(_ids(hits))
    again, _ = search.search_with_total("song kate", 10)
    assert _ids(again) == _ids(hits)


def test_isrc_collision_does_not_merge_tracks() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("505", 10)
    ids = {hit.id for hit in hits}
    assert _tid("t1-505-studio") in ids and _tid("t2-505-live") in ids
    assert total == 3


def test_pagination_stable_and_complete() -> None:
    search, _ = _search()
    full, total = search.search_with_total("505", 10)
    assert total == 3
    page1, total1 = search.search_with_total("505", 2, 0)
    page2, total2 = search.search_with_total("505", 2, 2)
    assert (total1, total2) == (3, 3)
    assert _ids(page1) + _ids(page2) == _ids(full)
    assert not set(_ids(page1)) & set(_ids(page2))
    empty, total3 = search.search_with_total("505", 2, 3)
    assert empty == [] and total3 == 3


def test_search_delegates_to_first_page() -> None:
    search, _ = _search()
    hits, _ = search.search_with_total("505", 2, 1)
    assert search.search("505", 2, 1) == hits
    assert search.search("505", 10) == search.search_with_total("505", 10)[0]


def test_bounds_rejected() -> None:
    search, _ = _search()
    with pytest.raises(MusicSearchError):
        search.search("   ", 10)
    with pytest.raises(MusicSearchError):
        search.search("505", 0)
    with pytest.raises(MusicSearchError):
        search.search("505", 21)
    with pytest.raises(MusicSearchError):
        search.search("505", 10, -1)
    with pytest.raises(MusicSearchError):
        search.search("505", 10, 201)
    with pytest.raises(TypeError):
        PostgresStagingSearch("not-a-factory")


def test_no_match_returns_empty_total_zero() -> None:
    search, _ = _search()
    hits, total = search.search_with_total("zzz-no-such-song", 10)
    assert hits == [] and total == 0


def test_credit_positions_preserved() -> None:
    search, session = _search()
    rows = session.execute(
        select(StagedTrackArtist.artist_id, StagedTrackArtist.position).where(
            StagedTrackArtist.track_id == _tid("t1-505-studio")
        )
    ).all()
    assert rows == [(artist_id_for_mbid(_M1), 0)]
    hits, _ = search.search_with_total("arctic monkeys 505", 1)
    assert hits[0].artists == ("Arctic Monkeys",)
