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

import math
import re
import threading
import time
import urllib.parse
import uuid

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse

from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal
from remanence.api.music_schemas import MusicSearchResponse, MusicSearchResultItem
from remanence.api.problems import problem_response
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

# Narrow per-user rate limit for the public authenticated search route.
# Token bucket: 60 requests/minute sustained, burst 10. Process-local only
# (no Redis); keyed on the authenticated principal (no IP fallback — the
# route is authenticated, so unauthenticated requests fail 401 in the auth
# dependency before ever reaching the limiter). Invalid queries and unwired
# backends (422/503) do not consume budget: the limiter runs after query
# parsing and the disabled check, so only valid requests to a wired backend
# are counted. Bounded memory: at most _RATE_LIMIT_MAX_USERS entries;
# idle/oldest entries are evicted (see _evict_if_needed).
_RATE_LIMIT_PER_MINUTE = 60
_RATE_LIMIT_BURST = 10
_RATE_LIMIT_MAX_USERS = 10_000
_RATE_LIMIT_IDLE_EVICT_SECONDS = 600.0
_RATE_LIMIT_REFILL_PER_SECOND = _RATE_LIMIT_PER_MINUTE / 60.0

_rate_limit_lock = threading.Lock()
# user_id -> [tokens, last_refill_monotonic, last_seen_monotonic]
_rate_limit_buckets: dict[uuid.UUID, list[float]] = {}


def reset_music_search_rate_limiter() -> None:
    """Clear all rate-limit state (tests only)."""
    with _rate_limit_lock:
        _rate_limit_buckets.clear()


def _evict_if_needed(now: float) -> None:
    """Bound memory: drop idle entries first, then oldest-seen."""
    if len(_rate_limit_buckets) <= _RATE_LIMIT_MAX_USERS:
        return
    idle_cutoff = now - _RATE_LIMIT_IDLE_EVICT_SECONDS
    idle = [
        key for key, bucket in _rate_limit_buckets.items() if bucket[2] < idle_cutoff
    ]
    for key in idle:
        del _rate_limit_buckets[key]
    if len(_rate_limit_buckets) <= _RATE_LIMIT_MAX_USERS:
        return
    overflow = len(_rate_limit_buckets) - _RATE_LIMIT_MAX_USERS
    oldest = sorted(_rate_limit_buckets.items(), key=lambda item: item[1][2])
    for key, _ in oldest[:overflow]:
        del _rate_limit_buckets[key]


def check_music_search_rate_limit(user_id: uuid.UUID) -> tuple[bool, int]:
    """Consume one token for ``user_id``.

    Returns ``(allowed, retry_after_seconds)``. ``retry_after`` is 0 when
    allowed, otherwise >= 1 (ceiled time until one token refills).
    """
    now = time.monotonic()
    with _rate_limit_lock:
        bucket = _rate_limit_buckets.get(user_id)
        if bucket is None:
            _rate_limit_buckets[user_id] = [
                float(_RATE_LIMIT_BURST - 1),
                now,
                now,
            ]
            _evict_if_needed(now)
            return True, 0
        tokens, last_refill, _ = bucket
        elapsed = max(0.0, now - last_refill)
        tokens = min(float(_RATE_LIMIT_BURST), tokens + elapsed * _RATE_LIMIT_REFILL_PER_SECOND)
        if tokens >= 1.0:
            bucket[0] = tokens - 1.0
            bucket[1] = now
            bucket[2] = now
            return True, 0
        deficit = 1.0 - tokens
        retry_after = max(1, math.ceil(deficit / _RATE_LIMIT_REFILL_PER_SECOND))
        bucket[1] = now
        bucket[2] = now
        _evict_if_needed(now)
        return False, retry_after


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
