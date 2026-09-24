"""Real Meilisearch adapter speaking the Meilisearch REST search API.

Wire protocol only: this adapter translates between the own domain and
Meilisearch HTTP. It is NOT a working real catalog — real MusicBrainz core
ingestion, Postgres ``music.*`` persistence, and index-revision management
(ARCH sections 5-9) are explicitly deferred; see ``music/SCOPE.md``.

PostgreSQL stays the source of truth; Meilisearch is a disposable read
index that can be rebuilt at any time (section 10). Live ranking intent
(section 12, R1) is split across two places by Meilisearch design:
index settings (``document.meilisearch_index_settings``) define what is
searchable/sortable, and the per-request payload built here applies a
variant-aware ``canonicalRank:asc`` sort for bare queries while lifting it
when the query names an explicit variant (e.g. "505 live").
``canonicalRank`` is numeric because Meilisearch cannot sort on the boolean
``isCanonical`` field (revoked F2 contract).

Isolation: the default URL uses port 17770, which is intentionally
different from the Meilisearch production default (7700) and from every
Remanence dev port (API 8000, Postgres 55432). Integration tests must only
use this isolated port (or ``REMANENCE_MUSIC_MEILI_URL``) and must never
touch production containers.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

from remanence.music.domain import TrackSearchResult, normalize_text
from remanence.music.ports import (
    SEARCH_LIMIT_MAX,
    SEARCH_OFFSET_MAX,
    MusicSearchError,
    MusicSearchUnavailableError,
)
from remanence.music.search.document import (
    MEILISEARCH_INDEX_UID,
    SEARCH_ATTRIBUTES_TO_RETRIEVE,
    VARIANT_TOKENS,
    parse_search_document_id,
)

# Isolated local default for tests/dev. See module docstring.
ISOLATED_MEILISEARCH_URL = "http://127.0.0.1:17770"

# Production-default Meilisearch port. Refused explicitly so a copy-pasted
# production URL can never become a test/dev target.
_PRODUCTION_DEFAULT_MEILI_PORT = 7700

# Upper bound for a single Meilisearch HTTP response body. The public API
# returns at most SEARCH_LIMIT_MAX mapped hits; anything larger is a
# misbehaving backend, not a larger catalog page.
MEILI_MAX_RESPONSE_BYTES = 256 * 1024


@dataclass(frozen=True, slots=True)
class MeilisearchConfig:
    base_url: str = ISOLATED_MEILISEARCH_URL
    api_key: str | None = None
    index_uid: str = MEILISEARCH_INDEX_UID
    timeout_s: float = 2.0

    def __post_init__(self) -> None:
        if type(self.base_url) is not str or not self.base_url.startswith("http"):
            raise ValueError("base_url must be an http(s) URL")
        try:
            parsed = urllib.parse.urlparse(self.base_url)
            port = parsed.port
        except ValueError:
            raise ValueError("base_url port must be an integer") from None
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("base_url must carry an explicit integer port 1-65535")
        if port == _PRODUCTION_DEFAULT_MEILI_PORT:
            raise ValueError("refusing production-default Meilisearch port 7700; use the isolated port")
        if not parsed.hostname:
            raise ValueError("base_url must include a hostname")
        if type(self.index_uid) is not str or not self.index_uid.strip():
            raise ValueError("index_uid must be non-empty")
        if self.api_key is not None and type(self.api_key) is not str:
            raise ValueError("api_key must be a string or None")
        if type(self.timeout_s) is bool or not isinstance(self.timeout_s, (int, float)):
            raise ValueError("timeout_s must be numeric")
        if not self.timeout_s > 0:
            raise ValueError("timeout_s must be positive")


def _names_variant(query: str) -> bool:
    tokens = tuple(t for t in normalize_text(query).split(" ") if t)
    return any(tok in tokens for tok in VARIANT_TOKENS)


def build_search_request_payload(query: str, limit: int, offset: int = 0) -> dict:
    """Build the live Meilisearch search request body (R1 contract).

    Always requests the explicit field allow-list. Applies
    ``canonicalRank:asc`` sort for bare queries so canonical/default
    recordings outrank live/remix/cover variants; lifts the sort when the
    query names an explicit variant. ``offset`` is passed through natively.
    """
    if type(query) is not str or not query.strip():
        raise MusicSearchError("invalid query")
    if type(limit) is not int or not 1 <= limit <= SEARCH_LIMIT_MAX:
        raise MusicSearchError("invalid limit")
    if type(offset) is not int or not 0 <= offset <= SEARCH_OFFSET_MAX:
        raise MusicSearchError("invalid offset")
    cleaned = query.strip()
    payload: dict = {
        "q": cleaned,
        "limit": limit,
        "offset": offset,
        "attributesToRetrieve": list(SEARCH_ATTRIBUTES_TO_RETRIEVE),
    }
    if not _names_variant(cleaned):
        payload["sort"] = ["canonicalRank:asc"]
    return payload


class MeilisearchMusicSearch:
    """Synchronous Meilisearch implementation of :class:`MusicSearchPort`."""

    def __init__(self, config: MeilisearchConfig | None = None) -> None:
        self._config = config or MeilisearchConfig()

    @property
    def config(self) -> MeilisearchConfig:
        return self._config

    def _search_url(self) -> str:
        return f"{self._config.base_url.rstrip('/')}/indexes/{self._config.index_uid}/search"

    def _documents_url(self) -> str:
        return f"{self._config.base_url.rstrip('/')}/indexes/{self._config.index_uid}/documents"

    def _settings_url(self) -> str:
        return f"{self._config.base_url.rstrip('/')}/indexes/{self._config.index_uid}/settings"

    def _index_url(self) -> str:
        return f"{self._config.base_url.rstrip('/')}/indexes/{self._config.index_uid}"

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self._config.api_key:
            headers["Authorization"] = f"Bearer {self._config.api_key}"
        return headers

    def _post(self, url: str, payload: object) -> dict:
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=body, headers=self._headers(), method="POST")
        return self._read_json(request)

    def _read_json(self, request: urllib.request.Request) -> dict:
        try:
            with urllib.request.urlopen(request, timeout=self._config.timeout_s) as response:
                raw = response.read(MEILI_MAX_RESPONSE_BYTES + 1)
        except TimeoutError as exc:
            raise MusicSearchUnavailableError("music search unavailable") from exc
        except urllib.error.URLError as exc:
            raise MusicSearchUnavailableError("music search unavailable") from exc
        except MusicSearchUnavailableError:
            raise
        except Exception as exc:
            raise MusicSearchError("music search failed") from exc
        if len(raw) > MEILI_MAX_RESPONSE_BYTES:
            raise MusicSearchError("music search failed")
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except Exception as exc:
            raise MusicSearchError("music search failed") from exc
        if type(decoded) is not dict:
            raise MusicSearchError("music search failed")
        return decoded

    def search(self, query: str, limit: int, offset: int = 0) -> list[TrackSearchResult]:
        """Port contract: one ranked page (see ``search_with_total``)."""
        hits, _total = self.search_with_total(query, limit, offset)
        return hits

    def search_with_total(
        self, query: str, limit: int, offset: int = 0
    ) -> tuple[list[TrackSearchResult], int]:
        payload = build_search_request_payload(query, limit, offset)
        decoded = self._post(self._search_url(), payload)
        total = decoded.get("estimatedTotalHits")
        if type(total) is not int or total < 0:
            raise MusicSearchError("music search failed")
        return self.parse_search_response(decoded, limit=limit), total

    # -- Test/ops index helpers (push only; ingestion itself is deferred) --

    def update_settings(self, settings: dict) -> int:
        """Push index settings; returns the Meilisearch task UID."""
        if type(settings) is not dict or not settings:
            raise MusicSearchError("invalid settings")
        request = urllib.request.Request(
            self._settings_url(),
            data=json.dumps(settings).encode("utf-8"),
            headers=self._headers(),
            method="PATCH",
        )
        decoded = self._read_json(request)
        return self._task_uid(decoded)

    def put_documents(self, documents: list[dict]) -> int:
        """Push search documents; returns the Meilisearch task UID."""
        if type(documents) is not list or not documents:
            raise MusicSearchError("invalid documents")
        decoded = self._post(self._documents_url(), documents)
        return self._task_uid(decoded)

    def delete_index(self) -> None:
        """Delete the whole index (test isolation only)."""
        request = urllib.request.Request(
            self._index_url(), headers=self._headers(), method="DELETE"
        )
        try:
            with urllib.request.urlopen(request, timeout=self._config.timeout_s) as response:
                response.read(MEILI_MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return
            raise MusicSearchError("music search failed") from exc
        except TimeoutError as exc:
            raise MusicSearchUnavailableError("music search unavailable") from exc
        except urllib.error.URLError as exc:
            raise MusicSearchUnavailableError("music search unavailable") from exc
        except (MusicSearchError, MusicSearchUnavailableError):
            raise
        except Exception as exc:
            raise MusicSearchError("music search failed") from exc

    def wait_for_task(self, task_uid: int, *, timeout_s: float = 10.0) -> None:
        """Poll a Meilisearch task until succeeded/failed (test helper)."""
        if type(task_uid) is not int or task_uid < 0:
            raise MusicSearchError("invalid task")
        deadline = time.monotonic() + timeout_s
        url = f"{self._config.base_url.rstrip('/')}/tasks/{task_uid}"
        while True:
            request = urllib.request.Request(url, headers=self._headers(), method="GET")
            try:
                with urllib.request.urlopen(request, timeout=self._config.timeout_s) as response:
                    raw = response.read(MEILI_MAX_RESPONSE_BYTES + 1)
            except TimeoutError as exc:
                raise MusicSearchUnavailableError("music search unavailable") from exc
            except urllib.error.URLError as exc:
                raise MusicSearchUnavailableError("music search unavailable") from exc
            except (MusicSearchError, MusicSearchUnavailableError):
                raise
            except Exception as exc:
                raise MusicSearchError("music search failed") from exc
            try:
                task = json.loads(raw.decode("utf-8"))
            except Exception as exc:
                raise MusicSearchError("music search failed") from exc
            status = task.get("status") if type(task) is dict else None
            if status == "succeeded":
                return
            if status == "failed":
                raise MusicSearchError("music search failed")
            if time.monotonic() >= deadline:
                raise MusicSearchError("music search failed")
            time.sleep(0.1)

    @staticmethod
    def _task_uid(decoded: dict) -> int:
        task_uid = decoded.get("taskUid")
        if type(task_uid) is not int or task_uid < 0:
            raise MusicSearchError("music search failed")
        return task_uid

    @staticmethod
    def parse_search_response(payload: object, *, limit: int) -> list[TrackSearchResult]:
        """Map a Meilisearch search response to own results (fail-closed)."""
        if type(payload) is not dict or type(payload.get("hits")) is not list:
            raise MusicSearchError("music search failed")
        results: list[TrackSearchResult] = []
        for hit in payload["hits"][:limit]:
            if type(hit) is not dict:
                raise MusicSearchError("music search failed")
            try:
                # Own-id gate (D13): only a canonical own UUID is accepted.
                # A provider identifier (e.g. MBID) here is never promoted
                # to a domain identity.
                track_id = parse_search_document_id(hit)
            except ValueError as exc:
                raise MusicSearchError("music search failed") from exc
            title = hit.get("title")
            artists = hit.get("artists")
            if type(title) is not str or not title.strip():
                raise MusicSearchError("music search failed")
            if type(artists) is not list or not artists or any(
                type(a) is not str or not a.strip() for a in artists
            ):
                raise MusicSearchError("music search failed")
            version = hit.get("version")
            release = hit.get("release")
            year = hit.get("year")
            duration_ms = hit.get("durationMs")
            artwork = hit.get("hasArtwork", hit.get("artworkAvailable", False))
            if version is not None and (type(version) is not str or not version.strip()):
                raise MusicSearchError("music search failed")
            if release is not None and (type(release) is not str or not release.strip()):
                raise MusicSearchError("music search failed")
            if year is not None and type(year) is not int:
                raise MusicSearchError("music search failed")
            if duration_ms is not None and (
                type(duration_ms) is not int or duration_ms <= 0
            ):
                raise MusicSearchError("music search failed")
            results.append(
                TrackSearchResult(
                    id=track_id,
                    title=title.strip(),
                    artists=tuple(a.strip() for a in artists),
                    version=version.strip() if isinstance(version, str) else None,
                    release=release.strip() if isinstance(release, str) else None,
                    year=year,
                    duration_ms=duration_ms,
                    artwork_available=bool(artwork),
                )
            )
        return results
