"""Isolated read-only FTS5 adapter; not wired into production composition.

AND-prefix retrieval (not Meili typo equivalence) with exact total and bounded
execution time. Existing MusicSearchPort/public own-ID result contract stays.
"""
from __future__ import annotations

import json
import re
import sqlite3
import time
import uuid
from pathlib import Path

from remanence.music.domain import TrackSearchResult, normalize_text
from remanence.music.ports import MusicSearchError, MusicSearchUnavailableError, SEARCH_LIMIT_MAX, SEARCH_OFFSET_MAX, SEARCH_QUERY_MAX_LENGTH
from remanence.music.search.document import VARIANT_TOKENS


class FullCatalogSearch:
    def __init__(self, database: Path, *, timeout_seconds=2.0):
        self.database = database.resolve()
        self.timeout_seconds = timeout_seconds

    def search(self, query: str, limit: int, offset: int = 0):
        return self.search_with_total(query, limit, offset)[0]

    def search_with_total(self, query: str, limit: int, offset: int = 0):
        if type(query) is not str or not 1 <= len(query.strip()) <= SEARCH_QUERY_MAX_LENGTH:
            raise MusicSearchError("invalid query")
        if type(limit) is not int or not 1 <= limit <= SEARCH_LIMIT_MAX or type(offset) is not int or not 0 <= offset <= SEARCH_OFFSET_MAX:
            raise MusicSearchError("invalid pagination")
        tokens = re.findall(r"\w+", normalize_text(query), re.UNICODE)
        if not tokens: raise MusicSearchError("invalid query")
        expression = " AND ".join('"' + token + '"*' for token in tokens)
        deadline = time.monotonic() + self.timeout_seconds
        connection = None
        try:
            connection = sqlite3.connect(f"file:{self.database}?mode=ro", uri=True)
            connection.execute("PRAGMA query_only=ON")
            connection.execute("PRAGMA cache_size=-16384")
            connection.set_progress_handler(lambda: int(time.monotonic() > deadline), 10000)
            if connection.execute("SELECT value FROM build_metadata WHERE key='state'").fetchone() != ("READY",):
                raise MusicSearchUnavailableError("catalog not ready")
            total = connection.execute("SELECT count(*) FROM track_fts WHERE track_fts MATCH ?", (expression,)).fetchone()[0]
            wants_variant = any(token in tokens for token in VARIANT_TOKENS)
            order = "bm25(track_fts,5.0,2.0,1.0)," + ("" if wants_variant else "t.canonical_rank,") + "t.id"
            rows = connection.execute("SELECT t.id,t.title,t.artists,t.duration_ms,t.version,t.recording_mbid FROM track_fts JOIN tracks t ON t.recording_id=track_fts.rowid WHERE track_fts MATCH ? ORDER BY " + order + " LIMIT ? OFFSET ?", (expression, limit, offset)).fetchall()
            return [TrackSearchResult(id=uuid.UUID(own_id), title=title, artists=tuple(json.loads(artists)),
                     duration_ms=duration, version=version, recording_mbid=mbid) for own_id,title,artists,duration,version,mbid in rows], total
        except sqlite3.Error as error:
            raise MusicSearchUnavailableError("full catalog search unavailable") from error
        finally:
            if connection is not None: connection.close()
