"""Authenticated music search endpoint (ARCHITECTURE-v1 section 26).

Contract: ``GET /music/v1/search?q=...&limit=...&offset=...``.
Authenticated like the directory endpoints. Query parsing is strict
allow-list (``q``, ``limit``, ``offset`` only); unknown params,
missing/blank ``q``, overlong queries, or out of range limits/offsets
yield fixed ``VALIDATION_FAILED`` without echoing input. Queries accept
UTF-8 (percent-encoded Cyrillic, accented Latin, CJK). The response
carries the ranked page plus the exact ``total`` match count and the
echoed ``offset``; omitting ``offset`` preserves the previous
offset-0 behavior. Backend outages — including a search backend that was
never wired (F1 fail-closed: no ``200 []`` mislead) — yield
``INTERNAL_UNAVAILABLE`` (503, retryable). Authenticated per-user rate
limit (60/min, burst 10) yields ``RATE_LIMITED`` (429, retryable,
``Retry-After``); unauthenticated requests fail 401 before rate limiting.
Overly broad staging queries fail closed 503 rather than materializing
unbounded candidate sets.
"""

from __future__ import annotations

import re
import urllib.parse
import uuid

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse

from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal
from remanence.api.music_schemas import MusicSearchResponse, MusicSearchResultItem
from remanence.api.problems import problem_response
from remanence.api.rate_limit import TokenBucketLimiter
from remanence.music.ports import (
    SEARCH_LIMIT_DEFAULT,
    SEARCH_LIMIT_MAX,
    SEARCH_OFFSET_DEFAULT,
    SEARCH_OFFSET_MAX,
    SEARCH_QUERY_MAX_LENGTH,
    MusicSearchError,
    MusicSearchUnavailableError,
)
from remanence.music.search.fixtures import FIXTURE_TRACKS
from remanence.music.search.in_memory import InMemoryMusicSearch
from remanence.settings import AppMode

router = APIRouter()

_ALLOWED_QUERY_KEYS = frozenset({"q", "limit", "offset"})
_LIMIT_RE = re.compile(r"^[0-9]+$")

# Per-user rate limit for the authenticated search route: 60/min, burst 10,
# keyed on the principal. The limiter runs after query parsing and the
# disabled check, so invalid queries and unwired backends consume no budget.
_RATE_LIMIT_BURST = 10
_RATE_LIMIT_MAX_USERS = 10_000
_music_search_limiter = TokenBucketLimiter(per_minute=60, burst=_RATE_LIMIT_BURST, max_keys=_RATE_LIMIT_MAX_USERS)
_rate_limit_buckets = _music_search_limiter._buckets


def reset_music_search_rate_limiter() -> None:
    """Clear all rate-limit state (tests only)."""
    _music_search_limiter.reset()


def check_music_search_rate_limit(user_id: uuid.UUID) -> tuple[bool, int]:
    """Consume one token for ``user_id``; return ``(allowed, retry_after)``."""
    return _music_search_limiter.check(user_id)


def get_music_search(request: Request):
    """Return the wired search port, or ``None`` when not configured (F1).

    There is deliberately no silent empty fallback: an unwired endpoint
    must fail closed with 503, never a misleading ``200 []``.
    """
    return getattr(request.app.state, "music_search", None)


def parse_music_search_query(request: Request) -> tuple[str, int, int]:
    raw = request.scope.get("query_string", b"")
    if raw is None:
        raw = b""
    if not isinstance(raw, (bytes, bytearray)):
        raise ValueError("invalid query")
    try:
        text = bytes(raw).decode("utf-8")
    except UnicodeDecodeError:
        raise ValueError("invalid query") from None
    params: dict[str, list[str]] = {}
    if text:
        for part in text.split("&"):
            if not part or "=" not in part:
                raise ValueError("invalid query")
            name, value = part.split("=", 1)
            if name not in _ALLOWED_QUERY_KEYS:
                raise ValueError("invalid query")
            params.setdefault(name, []).append(value)
    q_values = params.get("q", [])
    limit_values = params.get("limit", [])
    offset_values = params.get("offset", [])
    if len(q_values) != 1 or len(limit_values) > 1 or len(offset_values) > 1:
        raise ValueError("invalid query")
    try:
        query = urllib.parse.unquote_plus(q_values[0], encoding="utf-8", errors="strict")
    except Exception:
        raise ValueError("invalid query") from None
    if not query.strip() or len(query.strip()) > SEARCH_QUERY_MAX_LENGTH:
        raise ValueError("invalid query")
    limit = SEARCH_LIMIT_DEFAULT
    if limit_values:
        limit_text = limit_values[0]
        if _LIMIT_RE.fullmatch(limit_text) is None:
            raise ValueError("invalid query")
        try:
            limit = int(limit_text)
        except ValueError:
            raise ValueError("invalid query") from None
        if not 1 <= limit <= SEARCH_LIMIT_MAX:
            raise ValueError("invalid query")
    offset = SEARCH_OFFSET_DEFAULT
    if offset_values:
        offset_text = offset_values[0]
        if _LIMIT_RE.fullmatch(offset_text) is None:
            raise ValueError("invalid query")
        try:
            offset = int(offset_text)
        except ValueError:
            raise ValueError("invalid query") from None
        if not 0 <= offset <= SEARCH_OFFSET_MAX:
            raise ValueError("invalid query")
    return query.strip(), limit, offset


@router.get("/music/v1/search", response_model=MusicSearchResponse)
def search_music(
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    search=Depends(get_music_search),
) -> MusicSearchResponse | JSONResponse:
    try:
        query, limit, offset = parse_music_search_query(request)
    except ValueError:
        return problem_response(request, "VALIDATION_FAILED")
    if search is None:
        return problem_response(request, "INTERNAL_UNAVAILABLE")
    allowed, retry_after = check_music_search_rate_limit(principal.user_id)
    if not allowed:
        return problem_response(
            request,
            "RATE_LIMITED",
            extra_headers={"Retry-After": str(retry_after)},
        )
    try:
        hits, total = search.search_with_total(query, limit, offset)
    except MusicSearchUnavailableError:
        return problem_response(request, "INTERNAL_UNAVAILABLE")
    except MusicSearchError:
        return problem_response(request, "INTERNAL_ERROR")
    except Exception:
        return problem_response(request, "INTERNAL_ERROR")
    try:
        items = [
            MusicSearchResultItem(
                id=hit.id,
                title=hit.title,
                artists=list(hit.artists),
                version=hit.version,
                release=hit.release,
                year=hit.year,
                durationMs=hit.duration_ms,
                artworkAvailable=bool(hit.artwork_available),
                recordingMbid=hit.recording_mbid,
            )
            for hit in hits
        ]
        return MusicSearchResponse(results=items, total=total, offset=offset)
    except Exception:
        return problem_response(request, "INTERNAL_ERROR")


def build_fixture_music_search(mode: AppMode = AppMode.TEST) -> InMemoryMusicSearch:
    """Fixture-backed port for tests/dev only (F4 mode guard).

    Fixtures are NOT a user-facing catalog. Wiring fixtures in PROD is
    refused fail-closed so a real catalog can never be silently replaced
    by five hardcoded tracks.
    """
    if mode is not AppMode.TEST and mode is not AppMode.DEV:
        raise ValueError("fixture music search is test/dev only")
    return InMemoryMusicSearch(FIXTURE_TRACKS)
